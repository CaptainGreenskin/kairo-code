package com.example;

public class StringUtils {

    public static boolean isBlank(String str) {
        // BUG: calls str.trim() before null check, throws NPE on null input
        return str.trim().isEmpty() || str == null;
    }

    public static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public static String repeat(String str, int times) {
        if (str == null) return null;
        if (times <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(str);
        return sb.toString();
    }

    public static String reverse(String str) {
        if (str == null) return null;
        return new StringBuilder(str).reverse().toString();
    }
}
