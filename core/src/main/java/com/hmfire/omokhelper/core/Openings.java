package com.hmfire.omokhelper.core;

/**
 * 렌주 26주형 이름 알아보기.
 *
 * 흑 1수 기준으로 백 2수가 바로 옆(직접주, 13가지)인지 대각선(간접주, 13가지)인지,
 * 그리고 흑 3수가 5×5 안의 어디인지로 정해진다. 판을 돌리거나 뒤집어도 같은 주형으로 본다.
 * 좌표는 흑 1수를 (0,0)으로, 위쪽을 +y로 둔다.
 */
public final class Openings {
    private Openings() {}

    /** 직접주: 백 2수가 (0,1) */
    static final String[] DIRECT = {
            "한성(寒星)", "계월(溪月)", "소성(疏星)", "화월(花月)", "잔월(殘月)", "우월(雨月)", "금성(金星)",
            "송월(松月)", "구월(丘月)", "신월(新月)", "서성(瑞星)", "산월(山月)", "유성(遊星)"};
    static final int[][] DIRECT_POS = {
            {0, 2}, {1, 2}, {2, 2}, {1, 1}, {2, 1}, {1, 0}, {2, 0},
            {0, -1}, {1, -1}, {2, -1}, {0, -2}, {1, -2}, {2, -2}};

    /** 간접주: 백 2수가 (1,1) */
    static final String[] INDIRECT = {
            "장성(長星)", "협월(峽月)", "항성(恒星)", "수월(水月)", "유성(流星)", "운월(雲月)", "포월(浦月)",
            "남월(嵐月)", "은월(銀月)", "명성(明星)", "사월(斜月)", "명월(名月)", "혜성(彗星)"};
    static final int[][] INDIRECT_POS = {
            {2, 2}, {2, 1}, {2, 0}, {2, -1}, {2, -2}, {1, 0}, {1, -1},
            {1, -2}, {0, -1}, {0, -2}, {-1, -1}, {-1, -2}, {-2, -2}};

    /**
     * 판에 흑 2개, 백 1개만 있을 때 주형 이름 (예: "화월(花月) · 직접주"). 해당 없으면 null.
     */
    public static String name(int[] board, int size) {
        int b1 = -1, b2 = -1, w = -1, others = 0;
        for (int i = 0; i < board.length; i++) {
            if (board[i] == Position.BLACK) {
                if (b1 < 0) b1 = i;
                else if (b2 < 0) b2 = i;
                else others++;
            } else if (board[i] == Position.WHITE) {
                if (w < 0) w = i;
                else others++;
            }
        }
        if (b2 < 0 || w < 0 || others > 0) return null;
        int center = (size / 2) * size + size / 2;
        // 흑 1수는 보통 천원이므로 천원에 있는 돌을 먼저 1수로 본다
        int[][] orders = b2 == center ? new int[][]{{b2, b1}, {b1, b2}} : new int[][]{{b1, b2}, {b2, b1}};
        for (int[] o : orders) {
            String n = match(o[0], w, o[1], size);
            if (n != null) return n;
        }
        return null;
    }

    private static String match(int first, int second, int third, int size) {
        int x1 = first % size, y1 = -(first / size);
        int dx2 = second % size - x1, dy2 = -(second / size) - y1;
        int dx3 = third % size - x1, dy3 = -(third / size) - y1;
        if (Math.abs(dx2) > 1 || Math.abs(dy2) > 1 || (dx2 == 0 && dy2 == 0)) return null;
        for (int t = 0; t < 8; t++) {
            int[] a = transform(t, dx2, dy2);
            int[] b = transform(t, dx3, dy3);
            if (a[0] == 0 && a[1] == 1) {
                int i = indexOf(DIRECT_POS, b);
                if (i >= 0) return DIRECT[i] + " · 직접주";
            } else if (a[0] == 1 && a[1] == 1) {
                int i = indexOf(INDIRECT_POS, b);
                if (i >= 0) return INDIRECT[i] + " · 간접주";
            }
        }
        return null;
    }

    /** 8가지 대칭 (회전 4 × 뒤집기 2) */
    static int[] transform(int t, int x, int y) {
        if (t >= 4) x = -x;
        for (int r = 0; r < (t & 3); r++) {
            int nx = -y, ny = x;
            x = nx;
            y = ny;
        }
        return new int[]{x, y};
    }

    private static int indexOf(int[][] list, int[] p) {
        for (int i = 0; i < list.length; i++) if (list[i][0] == p[0] && list[i][1] == p[1]) return i;
        return -1;
    }
}
