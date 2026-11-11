package com.fastble.bledebugassistant.util;

import java.nio.charset.StandardCharsets;

/**
 * HEX / ASCII / 原始字节 互转工具。
 */
public final class HexUtil {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private HexUtil() {
    }

    /** 带空格的 HEX："AA BB CC" */
    public static String toHexString(byte[] data) {
        if (data == null || data.length == 0) return "";
        char[] out = new char[data.length * 3 - 1];
        int p = 0;
        for (byte b : data) {
            int v = b & 0xFF;
            out[p++] = HEX[v >>> 4];
            out[p++] = HEX[v & 0x0F];
            if (p < out.length) out[p++] = ' ';
        }
        return new String(out);
    }

    /** 不带空格的 HEX："AABBCC" */
    public static String toHexStringNoSpace(byte[] data) {
        if (data == null || data.length == 0) return "";
        char[] out = new char[data.length * 2];
        int p = 0;
        for (byte b : data) {
            int v = b & 0xFF;
            out[p++] = HEX[v >>> 4];
            out[p++] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 把 "AA BB cc" / "aabbcc" 这类字符串转成字节数组。
     *
     * @return null 表示格式非法
     */
    public static byte[] hexToBytes(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == '-' || c == ':') continue;
            if (isHexChar(c)) {
                sb.append(c);
            } else {
                return null;
            }
        }
        if (sb.length() == 0) return new byte[0];
        if (sb.length() % 2 != 0) return null;
        byte[] out = new byte[sb.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(sb.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 原版校验规则：([0-9|a-f|A-F]{2})* */
    public static boolean isHexString(String text) {
        if (text == null) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            if (!isHexChar(c)) return false;
            sb.append(c);
        }
        return sb.length() % 2 == 0;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 字节转可读 ASCII（不可打印字符用 '.'） */
    public static String toAscii(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(data.length);
        for (byte b : data) {
            int v = b & 0xFF;
            if (v == '\r') continue;
            if (v == '\n') {
                sb.append('\n');
            } else if (v >= 0x20 && v <= 0x7E) {
                sb.append((char) v);
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    public static byte[] asciiToBytes(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    public static String toReadableByteCount(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.2f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0);
    }
}
