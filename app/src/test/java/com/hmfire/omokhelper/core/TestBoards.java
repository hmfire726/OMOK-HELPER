package com.hmfire.omokhelper.core;

/** 테스트용 판 만들기 도우미 */
final class TestBoards {
    private TestBoards() {}

    /** black, white: "H8 I9 ..." 처럼 공백으로 구분한 좌표 */
    static int[] board(int size, String black, String white) {
        int[] b = new int[size * size];
        put(b, size, black, Position.BLACK);
        put(b, size, white, Position.WHITE);
        return b;
    }

    private static void put(int[] b, int size, String coords, int color) {
        if (coords == null || coords.trim().isEmpty()) return;
        for (String t : coords.trim().split("\\s+")) {
            int c = Coords.parse(t, size);
            if (c < 0) throw new IllegalArgumentException("bad coord " + t);
            if (b[c] != Position.EMPTY) throw new IllegalArgumentException("duplicate " + t);
            b[c] = color;
        }
    }

    static Position position(Rule rule, int toMove, String black, String white) {
        return new Position(15, rule, board(15, black, white), toMove);
    }

    static int cell(String coord) {
        return Coords.parse(coord, 15);
    }
}
