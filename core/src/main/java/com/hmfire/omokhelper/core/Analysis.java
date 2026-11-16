package com.hmfire.omokhelper.core;

import java.util.ArrayList;
import java.util.List;

/** 분석 결과: 추천 수 목록, 예상 수순, 탐색 정보 */
public final class Analysis {
    public final int size;
    /** 분석한 쪽 (지금 둘 차례) */
    public final int toMove;
    /** 이미 5목이 있으면 이긴 색, 아니면 EMPTY */
    public int winner = Position.EMPTY;
    public final List<Suggestion> moves = new ArrayList<>();
    /** 1순위 수부터 시작하는 예상 수순 */
    public int[] line = new int[0];
    /** 완료한 탐색 깊이 (0이면 즉답) */
    public int depth;
    public long nodes;
    public long elapsedMs;
    /** 한 줄 요약 (예: "흑 필승 수순 발견") */
    public String headline = "";

    public Analysis(int size, int toMove) {
        this.size = size;
        this.toMove = toMove;
    }

    public Suggestion best() {
        return moves.isEmpty() ? null : moves.get(0);
    }

    public static final class Suggestion {
        public final int cell;
        public final String reason;
        public final int score;
        /** 이 수를 두면 이기는 것이 확정 */
        public final boolean winning;
        /** 이 수를 두어도 지는 것이 확정 */
        public final boolean losing;

        public Suggestion(int cell, String reason, int score, boolean winning, boolean losing) {
            this.cell = cell;
            this.reason = reason;
            this.score = score;
            this.winning = winning;
            this.losing = losing;
        }
    }

    public static String colorName(int color) {
        return color == Position.BLACK ? "흑" : color == Position.WHITE ? "백" : "-";
    }
}
