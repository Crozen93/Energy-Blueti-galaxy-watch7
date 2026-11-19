package com.example.bluettiwatch.protocol;

import java.util.Arrays;

/** Modbus RTU поверх BLE, как в bluetti-bt-lib (адрес устройства всегда 1). */
public final class Modbus {
    private Modbus() {}

    public static final int FUNC_READ = 3;
    public static final int FUNC_WRITE = 6;

    public static byte[] readCommand(int address, int quantity) {
        return frame(FUNC_READ, address, quantity);
    }

    public static byte[] writeCommand(int address, int value) {
        return frame(FUNC_WRITE, address, value);
    }

    private static byte[] frame(int func, int a, int b) {
        byte[] cmd = new byte[8];
        cmd[0] = 1;
        cmd[1] = (byte) func;
        cmd[2] = (byte) ((a >> 8) & 0xFF);
        cmd[3] = (byte) (a & 0xFF);
        cmd[4] = (byte) ((b >> 8) & 0xFF);
        cmd[5] = (byte) (b & 0xFF);
        int crc = crc16(cmd, 0, 6);
        cmd[6] = (byte) (crc & 0xFF);
        cmd[7] = (byte) ((crc >> 8) & 0xFF);
        return cmd;
    }

    public static int readResponseLength(int quantity) {
        return 2 * quantity + 5;
    }

    public static final int WRITE_RESPONSE_LENGTH = 8;
    public static final int EXCEPTION_RESPONSE_LENGTH = 5;

    public static int crc16(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= data[i] & 0xFF;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) crc = (crc >>> 1) ^ 0xA001;
                else crc >>>= 1;
            }
        }
        return crc & 0xFFFF;
    }

    public static boolean crcOk(byte[] frame) {
        if (frame == null || frame.length < 4) return false;
        int crc = crc16(frame, 0, frame.length - 2);
        return (frame[frame.length - 2] & 0xFF) == (crc & 0xFF)
                && (frame[frame.length - 1] & 0xFF) == ((crc >> 8) & 0xFF);
    }

    public static boolean isException(byte[] frame) {
        return frame != null && frame.length >= 2 && (frame[1] & 0x80) != 0;
    }

    public static int exceptionCode(byte[] frame) {
        return frame.length >= 3 ? frame[2] & 0xFF : -1;
    }

    /** Данные регистров из ответа на чтение (без заголовка и CRC). */
    public static byte[] readPayload(byte[] frame) {
        return Arrays.copyOfRange(frame, 3, frame.length - 2);
    }

    public static int uint16(byte[] payload, int index) {
        return ((payload[2 * index] & 0xFF) << 8) | (payload[2 * index + 1] & 0xFF);
    }
}
