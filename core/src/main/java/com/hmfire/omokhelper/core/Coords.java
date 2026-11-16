package com.hmfire.omokhelper.core;

/** 좌표 표기: 열은 A부터(왼쪽→오른쪽), 행은 1부터(아래→위). 예) 15줄 판의 가운데 = H8 */
public final class Coords {
    private Coords() {}

    public static String name(int cell, int size) {
        int r = cell / size, c = cell % size;
        return String.valueOf((char) ('A' + c)) + (size - r);
    }

    /** "H8" → 칸 번호. 잘못된 표기면 -1 */
    public static int parse(String text, int size) {
        if (text == null || text.length() < 2) return -1;
        char ch = Character.toUpperCase(text.charAt(0));
        int c = ch - 'A';
        int num;
        try {
            num = Integer.parseInt(text.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
        int r = size - num;
        if (c < 0 || c >= size || r < 0 || r >= size) return -1;
        return r * size + c;
    }

    public static String line(int[] cells, int size, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length && i < max; i++) {
            if (i > 0) sb.append(" → ");
            sb.append(name(cells[i], size));
        }
        if (cells.length > max) sb.append(" …");
        return sb.toString();
    }
}
