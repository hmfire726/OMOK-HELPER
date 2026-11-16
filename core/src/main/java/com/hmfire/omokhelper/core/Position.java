package com.hmfire.omokhelper.core;

import java.util.Random;

/**
 * 오목판 상태. 돌을 놓거나 무를 때마다 주변 칸의 "모양"(Shape)을 바로 갱신해 둔다.
 * 그래서 탐색 중에는 표를 다시 계산하지 않고 shape 배열만 읽으면 된다.
 */
public final class Position {
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    /** 방향: 가로, 세로, ↘ 대각선, ↙ 대각선 */
    static final int[] DR = {0, 1, 1, 1};
    static final int[] DC = {1, 0, 1, -1};

    private static final int MAX_SIZE = 19;
    private static final long[][] ZOBRIST = new long[2][MAX_SIZE * MAX_SIZE];
    private static final long ZOBRIST_SIDE;
    static {
        Random r = new Random(0x0A0C_0E0F_1234L);
        for (int c = 0; c < 2; c++)
            for (int i = 0; i < ZOBRIST[c].length; i++) ZOBRIST[c][i] = r.nextLong();
        ZOBRIST_SIDE = r.nextLong();
    }

    public final int size;
    public final int area;
    public final Rule rule;

    final int[] board;
    /** shape[색-1][방향][칸] : 그 색이 그 칸에 두면 그 방향으로 생기는 모양 */
    final byte[][][] shape;
    /** 주변 2칸 안에 있는 돌 수 (후보 수를 고를 때 사용) */
    final int[] near;
    /** neighbours[방향][칸 * 10 + i] : 이웃 칸 번호, 판 밖이면 -1 */
    private final int[][] neighbours;
    private final byte[] blackTable;

    private final int[] history;
    private int moveCount;
    int toMove = BLACK;
    long hash;
    int stones;

    public Position(int size, Rule rule) {
        if (size < 5 || size > MAX_SIZE) throw new IllegalArgumentException("size " + size);
        this.size = size;
        this.area = size * size;
        this.rule = rule;
        this.board = new int[area];
        this.shape = new byte[2][4][area];
        this.near = new int[area];
        this.history = new int[area + 8];
        this.blackTable = rule == Rule.RENJU ? Shape.EXACT_FIVE : Shape.LONG_WINS;
        this.neighbours = new int[4][area * Shape.SPAN];
        for (int d = 0; d < 4; d++) {
            for (int cell = 0; cell < area; cell++) {
                int r = cell / size, c = cell % size;
                for (int i = 0; i < Shape.SPAN; i++) {
                    int k = Shape.offset(i);
                    int rr = r + k * DR[d], cc = c + k * DC[d];
                    neighbours[d][cell * Shape.SPAN + i] =
                            (rr < 0 || rr >= size || cc < 0 || cc >= size) ? -1 : rr * size + cc;
                }
            }
        }
        for (int cell = 0; cell < area; cell++)
            for (int d = 0; d < 4; d++) computeShape(cell, d);
    }

    /** 이미 돌이 놓인 판으로 시작한다. stonesOnBoard[칸] = EMPTY/BLACK/WHITE */
    public Position(int size, Rule rule, int[] stonesOnBoard, int toMove) {
        this(size, rule);
        if (stonesOnBoard.length != area) throw new IllegalArgumentException("board length");
        for (int cell = 0; cell < area; cell++) {
            if (stonesOnBoard[cell] != EMPTY) put(cell, stonesOnBoard[cell]);
        }
        setToMove(toMove);
    }

    public int get(int cell) {
        return board[cell];
    }

    public int get(int row, int col) {
        return board[row * size + col];
    }

    public int toMove() {
        return toMove;
    }

    public int stones() {
        return stones;
    }

    public void setToMove(int color) {
        if (color != toMove) {
            toMove = color;
            hash ^= ZOBRIST_SIDE;
        }
    }

    /** 차례를 넘긴다 (상대에게 필승 수순이 있는지 볼 때 사용) */
    void pass() {
        toMove = 3 - toMove;
        hash ^= ZOBRIST_SIDE;
    }

    public void play(int cell) {
        if (board[cell] != EMPTY) throw new IllegalStateException("occupied " + cell);
        put(cell, toMove);
        history[moveCount++] = cell;
        toMove = 3 - toMove;
        hash ^= ZOBRIST_SIDE;
    }

    public void undo() {
        int cell = history[--moveCount];
        toMove = 3 - toMove;
        hash ^= ZOBRIST_SIDE;
        remove(cell);
    }

    private void put(int cell, int color) {
        board[cell] = color;
        hash ^= ZOBRIST[color - 1][cell];
        stones++;
        touchNear(cell, 1);
        refreshAround(cell);
    }

    private void remove(int cell) {
        int color = board[cell];
        board[cell] = EMPTY;
        hash ^= ZOBRIST[color - 1][cell];
        stones--;
        touchNear(cell, -1);
        refreshAround(cell);
    }

    private void touchNear(int cell, int delta) {
        int r = cell / size, c = cell % size;
        for (int rr = Math.max(0, r - 2); rr <= Math.min(size - 1, r + 2); rr++)
            for (int cc = Math.max(0, c - 2); cc <= Math.min(size - 1, c + 2); cc++)
                near[rr * size + cc] += delta;
    }

    private void refreshAround(int cell) {
        for (int d = 0; d < 4; d++) {
            computeShape(cell, d);
            int base = cell * Shape.SPAN;
            int[] nb = neighbours[d];
            for (int i = 0; i < Shape.SPAN; i++) {
                int q = nb[base + i];
                if (q >= 0 && board[q] == EMPTY) computeShape(q, d);
            }
        }
    }

    private void computeShape(int cell, int d) {
        if (board[cell] != EMPTY) {
            shape[0][d][cell] = Shape.NONE;
            shape[1][d][cell] = Shape.NONE;
            return;
        }
        int codeB = 0, codeW = 0;
        int base = cell * Shape.SPAN;
        int[] nb = neighbours[d];
        for (int i = 0; i < Shape.SPAN; i++) {
            int q = nb[base + i];
            int p = Shape.POW3[i];
            if (q < 0) {
                codeB += 2 * p;
                codeW += 2 * p;
            } else {
                int s = board[q];
                if (s == BLACK) {
                    codeB += p;
                    codeW += 2 * p;
                } else if (s == WHITE) {
                    codeB += 2 * p;
                    codeW += p;
                }
            }
        }
        shape[0][d][cell] = blackTable[codeB];
        shape[1][d][cell] = Shape.LONG_WINS[codeW];
    }

    /** color가 cell에 두었을 때 d 방향 모양 */
    public int shape(int color, int d, int cell) {
        return shape[color - 1][d][cell];
    }

    public boolean makesFive(int color, int cell) {
        byte[][] s = shape[color - 1];
        return s[0][cell] == Shape.FIVE || s[1][cell] == Shape.FIVE
                || s[2][cell] == Shape.FIVE || s[3][cell] == Shape.FIVE;
    }

    /** 렌주룰에서 흑이 이 칸에 두는 것이 금수(3-3, 4-4, 장목)인가 */
    public boolean isForbidden(int cell) {
        if (rule != Rule.RENJU || board[cell] != EMPTY) return false;
        return forbidden(cell, 0);
    }

    /** color 쪽이 cell에 둘 수 있는가 (빈칸 + 렌주룰 흑 금수 아님) */
    public boolean isLegal(int color, int cell) {
        if (board[cell] != EMPTY) return false;
        return color != BLACK || !isForbidden(cell);
    }

    private boolean forbidden(int cell, int depth) {
        byte[][] s = shape[0];
        int fours = 0, threes = 0;
        boolean overline = false;
        for (int d = 0; d < 4; d++) {
            int v = s[d][cell];
            if (v == Shape.FIVE) return false;
            if (v == Shape.OVERLINE) overline = true;
            else if (v == Shape.DOUBLE_FOUR) fours += 2;
            else if (v == Shape.FOUR || v == Shape.OPEN_FOUR) fours++;
            else if (v == Shape.OPEN_THREE) threes++;
        }
        if (overline || fours >= 2) return true;
        if (threes < 2) return false;
        if (depth >= 3) return true;

        // 진짜 3인지 확인: 그 줄에서 금수가 아닌 자리로 열린4를 만들 수 있어야 한다.
        int threeDirs = 0;
        for (int d = 0; d < 4; d++) if (s[d][cell] == Shape.OPEN_THREE) threeDirs |= 1 << d;
        int savedToMove = toMove;
        setToMove(BLACK);
        play(cell);
        int real = 0;
        for (int d = 0; d < 4 && real < 2; d++) {
            if ((threeDirs & (1 << d)) == 0) continue;
            int base = cell * Shape.SPAN;
            for (int i = 0; i < Shape.SPAN; i++) {
                int q = neighbours[d][base + i];
                if (q < 0 || board[q] != EMPTY) continue;
                if (s[d][q] == Shape.OPEN_FOUR && !forbidden(q, depth + 1)) {
                    real++;
                    break;
                }
            }
        }
        undo();
        setToMove(savedToMove);
        return real >= 2;
    }

    /** 판 위에 이미 5목이 있으면 그 색, 없으면 EMPTY */
    public int winner() {
        for (int cell = 0; cell < area; cell++) {
            int color = board[cell];
            if (color == EMPTY) continue;
            int r = cell / size, c = cell % size;
            for (int d = 0; d < 4; d++) {
                int pr = r - DR[d], pc = c - DC[d];
                if (pr >= 0 && pr < size && pc >= 0 && pc < size && board[pr * size + pc] == color) continue;
                int len = 0, rr = r, cc = c;
                while (rr >= 0 && rr < size && cc >= 0 && cc < size && board[rr * size + cc] == color) {
                    len++;
                    rr += DR[d];
                    cc += DC[d];
                }
                if (len == 5 || (len > 5 && (color == WHITE || rule == Rule.FREESTYLE))) return color;
            }
        }
        return EMPTY;
    }

    /** 돌 개수로 차례를 추정 (흑이 먼저 둔다) */
    public static int guessToMove(int[] stonesOnBoard) {
        int b = 0, w = 0;
        for (int v : stonesOnBoard) {
            if (v == BLACK) b++;
            else if (v == WHITE) w++;
        }
        return b > w ? WHITE : BLACK;
    }

    public int[] snapshot() {
        return board.clone();
    }
}
