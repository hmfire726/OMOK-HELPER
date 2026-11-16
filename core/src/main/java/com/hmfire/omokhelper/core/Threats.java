package com.hmfire.omokhelper.core;

/**
 * 한 칸에 두었을 때의 위협 단계.
 *
 *   L_FIVE  5목 완성
 *   L_WIN   열린4 또는 4-4 → 상대가 막을 수 없음
 *   L_43    4-3 (4를 막게 한 뒤 3을 열린4로)
 *   L_33    3-3 (열린3 두 개)
 *   L_FOUR  4 하나 (상대가 반드시 막아야 함)
 *   L_THREE 열린3 하나 (막지 않으면 다음에 열린4)
 *   ILLEGAL 렌주룰 흑 금수
 */
final class Threats {
    private Threats() {}

    static final int ILLEGAL = -1, L_NONE = 0, L_THREE = 1, L_FOUR = 2, L_33 = 3, L_43 = 4, L_WIN = 5, L_FIVE = 6;

    /** 모양별 기본 점수 (Shape 상수 순서) */
    static final int[] WEIGHT = {0, 2, 6, 20, 30, 120, 150, 5000, 5000, 100_000, 0};

    static int level(Position p, int color, int c) {
        byte[][] s = p.shape[color - 1];
        int fours = 0, threes = 0;
        boolean open4 = false, over = false;
        for (int d = 0; d < 4; d++) {
            int v = s[d][c];
            if (v == Shape.FIVE) return L_FIVE;
            if (v == Shape.OPEN_FOUR) {
                fours++;
                open4 = true;
            } else if (v == Shape.DOUBLE_FOUR) {
                fours += 2;
            } else if (v == Shape.FOUR) {
                fours++;
            } else if (v == Shape.OPEN_THREE) {
                threes++;
            } else if (v == Shape.OVERLINE) {
                over = true;
            }
        }
        if (color == Position.BLACK && p.rule == Rule.RENJU && (over || fours >= 2 || threes >= 2)
                && p.isForbidden(c)) return ILLEGAL;
        if (open4 || fours >= 2) return L_WIN;
        if (fours == 1 && threes >= 1) return L_43;
        if (threes >= 2) return L_33;
        if (fours == 1) return L_FOUR;
        if (threes == 1) return L_THREE;
        return L_NONE;
    }

    /** 4방향 모양 점수 합 (정렬용) */
    static int value(Position p, int color, int c) {
        byte[][] s = p.shape[color - 1];
        return WEIGHT[s[0][c]] + WEIGHT[s[1][c]] + WEIGHT[s[2][c]] + WEIGHT[s[3][c]];
    }

    static boolean hasFour(Position p, int color, int c) {
        byte[][] s = p.shape[color - 1];
        return Shape.isFour(s[0][c]) || Shape.isFour(s[1][c]) || Shape.isFour(s[2][c]) || Shape.isFour(s[3][c]);
    }

    static boolean hasShape(Position p, int color, int c, int shape) {
        byte[][] s = p.shape[color - 1];
        return s[0][c] == shape || s[1][c] == shape || s[2][c] == shape || s[3][c] == shape;
    }

    /** color의 5목 자리 (없으면 -1). count에 서로 다른 자리 수(최대 2)를 넣는다 */
    static int fivePoint(Position p, int color, int[] count) {
        int first = -1, n = 0;
        for (int c = 0; c < p.area; c++) {
            if (p.board[c] != Position.EMPTY || p.near[c] == 0) continue;
            if (p.makesFive(color, c)) {
                if (first < 0) first = c;
                if (++n >= 2) break;
            }
        }
        if (count != null) count[0] = n;
        return first;
    }

    /** color가 다음 수에 막을 수 없는 모양(열린4, 4-4)을 만들 수 있는가 */
    static boolean hasWinThreat(Position p, int color) {
        for (int c = 0; c < p.area; c++) {
            if (p.board[c] != Position.EMPTY || p.near[c] == 0) continue;
            if (level(p, color, c) >= L_WIN) return true;
        }
        return false;
    }
}
