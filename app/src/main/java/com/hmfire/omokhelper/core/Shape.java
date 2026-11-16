package com.hmfire.omokhelper.core;

/**
 * 한 줄(가로/세로/대각선)에서 "이 칸에 돌을 놓으면 어떤 모양이 되는가"를 미리 계산해 둔 표.
 *
 * 놓을 칸을 가운데로 두고 양쪽 5칸씩, 이웃 10칸의 상태를 3진수 한 개로 만든다.
 *   0 = 빈칸, 1 = 내 돌, 2 = 막힘(상대 돌 또는 판 밖)
 * 3^10 = 59,049 가지 모양을 앱 시작 때 한 번만 계산하고, 이후에는 표만 찾아본다.
 */
public final class Shape {
    private Shape() {}

    public static final int NONE = 0;
    /** 돌 하나 — 더 두면 열린2가 될 수 있음 */
    public static final int ONE = 1;
    /** 닫힌 2 */
    public static final int TWO = 2;
    public static final int OPEN_TWO = 3;
    /** 닫힌 3 (한 수 더 두면 막힌 4) */
    public static final int THREE = 4;
    /** 열린 3 (한 수 더 두면 열린 4) */
    public static final int OPEN_THREE = 5;
    /** 막힌 4 (5목 자리가 하나) */
    public static final int FOUR = 6;
    /** 열린 4 (_XXXX_, 5목 자리가 둘) */
    public static final int OPEN_FOUR = 7;
    /** 한 줄 안의 4-4 (X_XXX_X 처럼 5목 자리가 떨어진 둘) */
    public static final int DOUBLE_FOUR = 8;
    public static final int FIVE = 9;
    /** 6목 이상 (렌주룰 흑에게는 승리가 아님) */
    public static final int OVERLINE = 10;

    static final int SIDE = 5;
    static final int SPAN = SIDE * 2;
    static final int[] POW3 = new int[SPAN + 1];
    static {
        POW3[0] = 1;
        for (int i = 1; i <= SPAN; i++) POW3[i] = POW3[i - 1] * 3;
    }

    /** 6목 이상도 승리로 치는 표 (자유룰, 렌주룰의 백) */
    static final byte[] LONG_WINS = build(true);
    /** 정확히 5목만 승리인 표 (렌주룰의 흑) */
    static final byte[] EXACT_FIVE = build(false);

    /** 이웃 i (0..9)가 11칸 줄에서 차지하는 위치. 가운데(5)는 놓을 칸. */
    static int lineIndex(int i) {
        return i < SIDE ? i : i + 1;
    }

    /** 이웃 i (0..9)의 놓을 칸 기준 거리: -5..-1, +1..+5 */
    static int offset(int i) {
        return i < SIDE ? i - SIDE : i - SIDE + 1;
    }

    private static byte[] build(boolean longWins) {
        byte[] table = new byte[POW3[SPAN]];
        java.util.Arrays.fill(table, (byte) -1);
        for (int code = 0; code < table.length; code++) classify(code, table, longWins);
        return table;
    }

    private static int classify(int code, byte[] table, boolean longWins) {
        if (table[code] >= 0) return table[code];
        int[] line = new int[SPAN + 1];
        int c = code;
        for (int i = 0; i < SPAN; i++) {
            line[lineIndex(i)] = c % 3;
            c /= 3;
        }
        line[SIDE] = 1;

        int run = 1;
        for (int k = SIDE - 1; k >= 0 && line[k] == 1; k--) run++;
        for (int k = SIDE + 1; k <= SPAN && line[k] == 1; k++) run++;

        int result;
        if (run >= 6) {
            result = longWins ? FIVE : OVERLINE;
        } else if (run == 5) {
            result = FIVE;
        } else {
            int wins = 0, firstWin = -1, lastWin = -1;
            int best = NONE;
            for (int i = 0; i < SPAN; i++) {
                int li = lineIndex(i);
                if (line[li] != 0) continue;
                int next = classify(code + POW3[i], table, longWins);
                if (next == FIVE) {
                    wins++;
                    if (firstWin < 0) firstWin = li;
                    lastWin = li;
                } else {
                    best = Math.max(best, weaker(next));
                }
            }
            if (wins >= 2) {
                result = (wins == 2 && lastWin - firstWin == 5) ? OPEN_FOUR : DOUBLE_FOUR;
            } else if (wins == 1) {
                result = FOUR;
            } else {
                result = best;
            }
        }
        table[code] = (byte) result;
        return result;
    }

    /** 한 수 더 두었을 때 s가 된다면, 지금 모양은 무엇인가 */
    private static int weaker(int s) {
        switch (s) {
            case OPEN_FOUR:
            case DOUBLE_FOUR:
                return OPEN_THREE;
            case FOUR:
                return THREE;
            case OPEN_THREE:
                return OPEN_TWO;
            case THREE:
                return TWO;
            case OPEN_TWO:
                return ONE;
            default:
                return NONE;
        }
    }

    public static boolean isFour(int s) {
        return s == FOUR || s == OPEN_FOUR || s == DOUBLE_FOUR;
    }
}
