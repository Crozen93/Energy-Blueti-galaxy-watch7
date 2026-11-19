package com.example.bluettiwatch.protocol;

public final class Hex {
    private Hex() {}

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    public static byte[] decode(String s) {
        int n = s.length();
        if (n % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) | Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    public static String encode(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = DIGITS[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = DIGITS[b[i] & 0xF];
        }
        return new String(out);
    }
}
