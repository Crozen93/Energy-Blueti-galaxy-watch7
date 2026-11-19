package com.example.bluettiwatch.protocol;

import java.io.ByteArrayOutputStream;

/**
 * Состояние протокола поверх «сырых» BLE-уведомлений, независимо от Android.
 * Собирает фрагменты, проводит рукопожатие и отдаёт целые Modbus-кадры.
 */
public final class BluettiSession {

    public interface Callbacks {
        /** Отправить байты в характеристику записи (FF02). */
        void send(byte[] data);

        /** Рукопожатие завершено, можно слать команды. */
        void onReady();

        /** Получен полный Modbus-кадр ответа. */
        void onFrame(byte[] frame);

        void onProtocolError(String message);
    }

    private final BluettiCrypto crypto;
    private final Callbacks cb;
    private boolean encrypted;
    private boolean sawChallenge;
    private boolean readySignalled;

    private final ByteArrayOutputStream encBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream plainBuf = new ByteArrayOutputStream();
    private int expectedPlainLen = -1;

    public BluettiSession(BluettiCrypto crypto, boolean encrypted, Callbacks cb) {
        this.crypto = crypto;
        this.encrypted = encrypted;
        this.cb = cb;
    }

    public synchronized boolean isEncrypted() {
        return encrypted;
    }

    public synchronized boolean sawChallenge() {
        return sawChallenge;
    }

    public synchronized boolean isReady() {
        return !encrypted || crypto.isReady();
    }

    /** Станция не начала рукопожатие — работаем без шифрования (старые прошивки). */
    public synchronized void useUnencrypted() {
        encrypted = false;
        signalReady();
    }

    /** Подготовить команду к отправке; сбрасывает буферы под новый ответ. */
    public synchronized byte[] wrapCommand(byte[] modbus, int expectedResponseLength) {
        encBuf.reset();
        plainBuf.reset();
        expectedPlainLen = expectedResponseLength;
        return encrypted ? crypto.encryptCommand(modbus) : modbus;
    }

    public synchronized void onNotification(byte[] data) {
        try {
            if (encrypted) handleEncrypted(data);
            else handlePlain(data);
        } catch (Exception e) {
            encBuf.reset();
            cb.onProtocolError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handlePlain(byte[] data) {
        plainBuf.write(data, 0, data.length);
        byte[] buf = plainBuf.toByteArray();
        boolean exception = Modbus.isException(buf) && buf.length >= Modbus.EXCEPTION_RESPONSE_LENGTH;
        if (exception || (expectedPlainLen > 0 && buf.length >= expectedPlainLen)) {
            plainBuf.reset();
            cb.onFrame(buf);
        }
    }

    private void handleEncrypted(byte[] data) throws Exception {
        if (BluettiCrypto.isPreKeyExchange(data)) {
            if (!BluettiCrypto.checksumOk(data)) {
                cb.onProtocolError("Ошибка контрольной суммы рукопожатия");
            }
            int type = BluettiCrypto.messageType(data);
            if (type == BluettiCrypto.TYPE_CHALLENGE) {
                sawChallenge = true;
                cb.send(crypto.onChallenge(data));
            }
            // TYPE_CHALLENGE_ACCEPTED — ничего не делаем
            return;
        }

        if (!crypto.hasUnsecureKey()) {
            cb.onProtocolError("Зашифрованное сообщение до инициализации ключа");
            return;
        }

        encBuf.write(data, 0, data.length);
        while (true) {
            byte[] buf = encBuf.toByteArray();
            int expected = crypto.expectedEncryptedLength(buf, buf.length);
            if (expected < 0 || buf.length < expected) return;

            byte[] complete = java.util.Arrays.copyOfRange(buf, 0, expected);
            encBuf.reset();
            if (buf.length > expected) encBuf.write(buf, expected, buf.length - expected);

            byte[] plain = crypto.decryptIncoming(complete);

            if (BluettiCrypto.isPreKeyExchange(plain)) {
                int type = BluettiCrypto.messageType(plain);
                if (type == BluettiCrypto.TYPE_PEER_PUBKEY) {
                    cb.send(crypto.onPeerPubkey(plain));
                } else if (type == BluettiCrypto.TYPE_PUBKEY_ACCEPTED) {
                    crypto.onKeyAccepted(plain);
                    signalReady();
                }
            } else {
                cb.onFrame(plain);
            }
            if (encBuf.size() == 0) return;
        }
    }

    private void signalReady() {
        if (!readySignalled) {
            readySignalled = true;
            cb.onReady();
        }
    }
}
