package com.hmfire.omokhelper.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 추천 엔진.
 *
 * 1. 즉시 판단: 5목 완성, 상대 4 막기
 * 2. VCF: 4를 연달아 두어 이기는 수순 찾기 (나 / 상대 모두)
 * 3. 반복 심화 알파-베타(negamax + PVS) 탐색: 정해진 생각 시간 안에서 1수, 2수, 3수 … 앞까지 읽는다.
 *    치환표(Zobrist 해시), 위협 확장(상대 4는 반드시 막으므로 깊이를 줄이지 않음), 후보 수 제한 사용.
 */
public final class Engine {
    /** 앱에서 고를 수 있는 생각 시간(ms) */
    public static final int[] THINK_TIMES_MS = {500, 1500, 3000, 4000, 5000};

    static final int WIN = 10_000_000;
    static final int WIN_SURE = WIN - 1000;
    private static final int INF = WIN + 1;
    private static final int MAX_PLY = 64;

    /** 칸 하나의 위협 단계 */
    static final int ILLEGAL = -1, L_NONE = 0, L_THREE = 1, L_FOUR = 2, L_33 = 3, L_43 = 4, L_WIN = 5, L_FIVE = 6;

    /** 모양별 점수 (Shape 상수 순서) */
    private static final int[] WEIGHT = {0, 2, 6, 20, 30, 120, 150, 5000, 5000, 100_000, 0};

    private final Position pos;

    private volatile boolean cancelled;
    private long deadline;
    private boolean stopped;
    private long nodes;

    // 치환표
    private static final int TT_BITS = 18;
    private final long[] ttKey = new long[1 << TT_BITS];
    private final int[] ttScore = new int[1 << TT_BITS];
    private final short[] ttMove = new short[1 << TT_BITS];
    private final byte[] ttDepth = new byte[1 << TT_BITS];
    private final byte[] ttFlag = new byte[1 << TT_BITS];
    private static final byte EXACT = 1, LOWER = 2, UPPER = 3;

    // VCF 실패 기억
    private static final int VCF_BITS = 16;
    private final long[] vcfFailKey = new long[1 << VCF_BITS];
    private final byte[] vcfFailDepth = new byte[1 << VCF_BITS];

    private final int[][] moveBuf = new int[MAX_PLY][];
    private final int[][] scoreBuf = new int[MAX_PLY][];
    private final int[][] pv = new int[MAX_PLY + 1][MAX_PLY + 1];
    private final int[] pvLen = new int[MAX_PLY + 1];

    // evalCell 결과
    private int lastLevel;

    public Engine(Position pos) {
        this.pos = pos;
        for (int i = 0; i < MAX_PLY; i++) {
            moveBuf[i] = new int[pos.area];
            scoreBuf[i] = new int[pos.area];
        }
    }

    /** 다른 스레드에서 탐색을 멈출 때 */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // ------------------------------------------------------------------ 분석

    public Analysis analyze(long thinkMs, int topN) {
        long start = System.currentTimeMillis();
        final int me = pos.toMove, opp = 3 - me;
        Analysis result = new Analysis(pos.size, me);
        topN = Math.max(1, topN);
        nodes = 0;
        stopped = false;

        int won = pos.winner();
        if (won != Position.EMPTY) {
            result.winner = won;
            result.headline = Analysis.colorName(won) + " 승리 — 게임 끝";
            return finish(result, start);
        }

        if (pos.stones == 0) {
            int center = (pos.size / 2) * pos.size + pos.size / 2;
            result.moves.add(new Analysis.Suggestion(center, "첫 수 — 가운데 (천원)", 0, false, false));
            result.line = new int[]{center};
            result.headline = "첫 수";
            return finish(result, start);
        }

        // 1) 바로 5목
        List<Integer> myFives = fivePoints(me);
        if (!myFives.isEmpty()) {
            for (int i = 0; i < myFives.size() && i < topN; i++)
                result.moves.add(new Analysis.Suggestion(myFives.get(i), "5목 완성 (승리)", WIN, true, false));
            result.line = new int[]{myFives.get(0)};
            result.headline = "이번 수로 승리";
            return finish(result, start);
        }

        // 2) 상대 4 막기
        List<Integer> oppFives = fivePoints(opp);
        if (!oppFives.isEmpty()) {
            boolean cantBlock = oppFives.size() >= 2;
            for (int cell : oppFives) {
                boolean forbidden = !pos.isLegal(me, cell);
                String reason = forbidden ? "상대 4 막기 — 하지만 금수 자리" : "상대 4 막기 (필수)";
                result.moves.add(new Analysis.Suggestion(cell, reason, cantBlock || forbidden ? -WIN : 0,
                        false, cantBlock || forbidden));
                if (result.moves.size() >= topN) break;
            }
            result.line = new int[]{oppFives.get(0)};
            result.headline = cantBlock ? "상대 4가 두 곳 — 막기 어려움" : "상대 4 — 바로 막아야 함";
            if (!cantBlock && pos.isLegal(me, oppFives.get(0))) {
                // 막은 다음의 예상 수순도 읽어서 보여 준다
                pos.play(oppFives.get(0));
                Analysis next = new Analysis(pos.size, opp);
                searchRoot(Math.max(100, thinkMs - (System.currentTimeMillis() - start)), 1, next);
                pos.undo();
                int[] l = new int[1 + next.line.length];
                l[0] = oppFives.get(0);
                System.arraycopy(next.line, 0, l, 1, next.line.length);
                result.line = l;
                result.depth = next.depth + 1;
            }
            return finish(result, start);
        }

        // 3) 나의 VCF (연속 4로 이기기)
        deadline = start + Math.max(100, thinkMs / 4);
        int[] myVcf = findVcf(me, 24);
        if (myVcf != null) {
            result.moves.add(new Analysis.Suggestion(myVcf[0], describe(myVcf[0], me, opp) + " — VCF 필승", WIN, true, false));
            result.line = myVcf;
            result.headline = Analysis.colorName(me) + " 필승 수순 (연속 4) 발견";
            if (topN > 1) {
                // 나머지 추천도 채워 둔다
                deadline = start + thinkMs;
                List<Analysis.Suggestion> rest = searchRoot(Math.max(50, thinkMs - (System.currentTimeMillis() - start)), topN, result);
                for (Analysis.Suggestion s : rest) {
                    if (s.cell != myVcf[0] && result.moves.size() < topN) result.moves.add(s);
                }
                result.line = myVcf;
            }
            return finish(result, start);
        }
        if (cancelled) return finish(result, start);

        // 4) 깊이 탐색
        deadline = start + thinkMs;
        List<Analysis.Suggestion> list = searchRoot(thinkMs - (System.currentTimeMillis() - start), topN, result);
        result.moves.addAll(list);
        if (!list.isEmpty()) {
            Analysis.Suggestion b = list.get(0);
            if (b.winning) result.headline = Analysis.colorName(me) + " 필승 수순 발견";
            else if (b.losing) result.headline = "상대 필승 수순 있음 — 최대한 버티기";
            else result.headline = Analysis.colorName(me) + " 차례";
        }
        return finish(result, start);
    }

    private Analysis finish(Analysis a, long start) {
        a.elapsedMs = System.currentTimeMillis() - start;
        a.nodes += nodes;
        return a;
    }

    /** 반복 심화로 루트의 모든 후보를 평가하고 상위 topN 개를 돌려준다 */
    private List<Analysis.Suggestion> searchRoot(long budgetMs, int topN, Analysis result) {
        final int me = pos.toMove, opp = 3 - me;
        long now = System.currentTimeMillis();
        deadline = now + Math.max(30, budgetMs);
        stopped = false;

        int count = generateMoves(0, me, opp, true);
        if (count == -2) {
            // 열린4 / 4-4로 바로 이기는 수가 있다
            int w = winningMove;
            List<Analysis.Suggestion> out = new ArrayList<>();
            out.add(new Analysis.Suggestion(w, describe(w, me, opp) + " (필승)", WIN, true, false));
            result.line = new int[]{w};
            result.depth = 1;
            return out;
        }
        if (count <= 0) {
            // 둘 곳이 없거나 모두 금수
            return new ArrayList<>();
        }
        int[] moves = Arrays.copyOf(moveBuf[0], count);
        String[] reasons = new String[count];
        for (int i = 0; i < count; i++) reasons[i] = describe(moves[i], me, opp);

        // 상대에게 필승 수순(VCF)이 있는지: 내가 한 수 쉬었다고 가정하고 본다
        boolean[] stillLoses = new boolean[count];
        long vcfBudgetEnd = System.currentTimeMillis() + Math.max(40, budgetMs / 6);
        pos.pass();
        long savedDeadline = deadline;
        deadline = vcfBudgetEnd;
        int[] oppVcf = findVcf(opp, 20);
        pos.pass();
        if (oppVcf != null && !stopped) {
            long each = Math.max(5, (vcfBudgetEnd + budgetMs / 6 - System.currentTimeMillis()) / count);
            for (int i = 0; i < count && !cancelled; i++) {
                pos.play(moves[i]);
                deadline = System.currentTimeMillis() + each;
                stopped = false;
                stillLoses[i] = findVcf(opp, 20) != null;
                pos.undo();
            }
        }
        deadline = savedDeadline;
        stopped = false;

        int[] scores = new int[count];
        int[][] lines = new int[count][];
        int[] bestScores = new int[count];
        int[][] bestLines = new int[count][];
        int completedDepth = 0;

        for (int depth = 1; depth <= 30; depth++) {
            int[] order = orderByScores(depth == 1 ? null : bestScores, count);
            Arrays.fill(scores, -INF);
            boolean complete = true;
            for (int k = 0; k < count; k++) {
                int i = order[k];
                pos.play(moves[i]);
                int score;
                int threshold = kthBest(scores, topN);
                if (k < topN || threshold <= -INF) {
                    score = -search(depth - 1, -INF, INF, 1);
                } else {
                    score = -search(depth - 1, -threshold - 1, -threshold, 1);
                    if (score > threshold && !stopped) score = -search(depth - 1, -INF, -threshold, 1);
                }
                pos.undo();
                if (stopped) {
                    complete = false;
                    break;
                }
                if (stillLoses[i] && score > -WIN_SURE) {
                    // 상대 VCF를 못 막는 수: 지는 수로 표시하되 서로의 순서는 유지
                    score = -WIN_SURE - 401 + Math.max(-400, Math.min(400, score / 100));
                }
                scores[i] = score;
                int[] l = new int[1 + pvLen[1]];
                l[0] = moves[i];
                System.arraycopy(pv[1], 0, l, 1, pvLen[1]);
                lines[i] = l;
            }
            if (!complete) break;
            completedDepth = depth;
            System.arraycopy(scores, 0, bestScores, 0, count);
            for (int i = 0; i < count; i++) bestLines[i] = lines[i];
            int top = max(bestScores);
            // 이기거나 지는 것이 확정되면 더 읽을 필요 없음
            if (top >= WIN_SURE || allLosing(bestScores)) break;
        }

        if (completedDepth == 0) {
            // 시간이 너무 짧아 한 바퀴도 못 돌았으면 순서 점수를 그대로 쓴다
            for (int i = 0; i < count; i++) {
                bestScores[i] = scoreBuf[0][i];
                bestLines[i] = new int[]{moves[i]};
            }
        }

        Integer[] idx = new Integer[count];
        for (int i = 0; i < count; i++) idx[i] = i;
        final int[] fs = bestScores;
        Arrays.sort(idx, (a, b) -> Integer.compare(fs[b], fs[a]));

        List<Analysis.Suggestion> out = new ArrayList<>();
        for (int k = 0; k < count && out.size() < topN; k++) {
            int i = idx[k];
            int s = bestScores[i];
            boolean winning = s >= WIN_SURE;
            boolean losing = s <= -WIN_SURE;
            String reason = reasons[i];
            if (winning) reason += " (필승)";
            else if (losing) reason += " (불리)";
            out.add(new Analysis.Suggestion(moves[i], reason, s, winning, losing));
        }
        if (!out.isEmpty()) {
            int[] l = bestLines[idx[0]];
            result.line = l == null ? new int[]{moves[idx[0]]} : l;
        }
        result.depth = completedDepth;
        return out;
    }

    private static int[] orderByScores(int[] scores, int n) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        if (scores != null) {
            final int[] fs = scores;
            Arrays.sort(idx, (a, b) -> Integer.compare(fs[b], fs[a]));
        }
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = idx[i];
        return order;
    }

    private static int kthBest(int[] scores, int k) {
        int[] copy = scores.clone();
        Arrays.sort(copy);
        int idx = copy.length - k;
        return idx < 0 ? -INF : copy[idx];
    }

    private static int max(int[] a) {
        int m = -INF;
        for (int v : a) m = Math.max(m, v);
        return m;
    }

    private static boolean allLosing(int[] a) {
        for (int v : a) if (v > -WIN_SURE) return false;
        return true;
    }

    // ------------------------------------------------------------------ 알파-베타

    private int winningMove;

    private int search(int depth, int alpha, int beta, int ply) {
        pvLen[ply] = 0;
        if ((++nodes & 511) == 0) checkTime();
        if (stopped) return 0;
        final int me = pos.toMove, opp = 3 - me;
        if (ply >= MAX_PLY - 2) return evaluate(ply);

        // 바로 5목 / 상대 4
        int oppFive = -1, oppFiveCount = 0;
        final int area = pos.area;
        final int[] board = pos.board, near = pos.near;
        for (int c = 0; c < area; c++) {
            if (board[c] != Position.EMPTY || near[c] == 0) continue;
            if (pos.makesFive(me, c)) {
                pv[ply][0] = c;
                pvLen[ply] = 1;
                return WIN - ply - 1;
            }
            if (pos.makesFive(opp, c)) {
                if (oppFive != c) oppFiveCount++;
                oppFive = c;
            }
        }
        if (oppFiveCount >= 2) return -(WIN - ply - 2);
        if (oppFiveCount == 1) {
            if (!pos.isLegal(me, oppFive)) return -(WIN - ply - 2);
            pos.play(oppFive);
            int v = -search(depth, -beta, -alpha, ply + 1);
            pos.undo();
            setPv(ply, oppFive);
            return v;
        }

        if (depth <= 0) return evaluate(ply);

        // 치환표
        long key = pos.hash;
        int slot = (int) (key & ((1 << TT_BITS) - 1));
        int ttBest = -1;
        boolean nullWindow = beta - alpha == 1;
        if (ttKey[slot] == key) {
            ttBest = ttMove[slot];
            if (nullWindow && ttDepth[slot] >= depth) {
                int s = fromTT(ttScore[slot], ply);
                byte f = ttFlag[slot];
                if (f == EXACT || (f == LOWER && s >= beta) || (f == UPPER && s <= alpha)) return s;
            }
        }

        int count = generateMoves(ply, me, opp, false);
        if (count == -2) {
            pv[ply][0] = winningMove;
            pvLen[ply] = 1;
            return WIN - ply - 3;
        }
        if (count <= 0) return evaluate(ply);
        int[] moves = moveBuf[ply];
        if (ttBest >= 0) {
            for (int i = 1; i < count; i++) {
                if (moves[i] == ttBest) {
                    moves[i] = moves[0];
                    moves[0] = ttBest;
                    break;
                }
            }
        }

        int bestScore = -INF, bestMove = moves[0];
        int origAlpha = alpha;
        for (int i = 0; i < count; i++) {
            int m = moves[i];
            pos.play(m);
            int v;
            if (i == 0) {
                v = -search(depth - 1, -beta, -alpha, ply + 1);
            } else {
                v = -search(depth - 1, -alpha - 1, -alpha, ply + 1);
                if (v > alpha && v < beta && !stopped) v = -search(depth - 1, -beta, -alpha, ply + 1);
            }
            pos.undo();
            if (stopped) return 0;
            if (v > bestScore) {
                bestScore = v;
                bestMove = m;
                if (v > alpha) {
                    alpha = v;
                    setPv(ply, m);
                    if (alpha >= beta) break;
                }
            }
        }

        byte flag = bestScore <= origAlpha ? UPPER : bestScore >= beta ? LOWER : EXACT;
        ttKey[slot] = key;
        ttScore[slot] = toTT(bestScore, ply);
        ttMove[slot] = (short) bestMove;
        ttDepth[slot] = (byte) Math.min(depth, 127);
        ttFlag[slot] = flag;
        return bestScore;
    }

    private void setPv(int ply, int move) {
        pv[ply][0] = move;
        int n = pvLen[ply + 1];
        System.arraycopy(pv[ply + 1], 0, pv[ply], 1, n);
        pvLen[ply] = n + 1;
    }

    private static int toTT(int s, int ply) {
        if (s >= WIN_SURE) return s + ply;
        if (s <= -WIN_SURE) return s - ply;
        return s;
    }

    private static int fromTT(int s, int ply) {
        if (s >= WIN_SURE) return s - ply;
        if (s <= -WIN_SURE) return s + ply;
        return s;
    }

    private void checkTime() {
        if (cancelled || System.currentTimeMillis() >= deadline) stopped = true;
    }

    /**
     * 후보 수를 moveBuf[ply]에 점수 순으로 채운다.
     * @return 후보 개수, 열린4/4-4로 바로 이기는 수가 있으면 -2 (winningMove에 저장)
     */
    private int generateMoves(int ply, int me, int opp, boolean root) {
        final int area = pos.area;
        final int[] board = pos.board, near = pos.near;
        int[] moves = moveBuf[ply];
        int[] scores = scoreBuf[ply];

        int oppMaxLevel = L_NONE;
        int oppFive = -1, myWin = -1;
        int n = 0;
        // 1차: 모든 후보의 공격/수비 가치
        for (int c = 0; c < area; c++) {
            if (board[c] != Position.EMPTY || near[c] == 0) continue;
            int atk = evalCell(me, c);
            int myLevel = lastLevel;
            if (myLevel == ILLEGAL) continue;
            if (myLevel == L_FIVE) {
                winningMove = c;
                return -2;
            }
            if (myLevel == L_WIN && myWin < 0) myWin = c;
            int def = evalCell(opp, c);
            int oppLevel = lastLevel;
            if (oppLevel > oppMaxLevel) oppMaxLevel = oppLevel;
            if (oppLevel == L_FIVE) oppFive = c;
            moves[n] = c;
            // 수비 가치는 조금 낮게, 내 4/필승 모양은 높게
            scores[n] = atk + def * 7 / 8 + (myLevel >= L_FOUR ? 200 : 0);
            n++;
        }
        if (n == 0 && pos.stones == 0) {
            moves[0] = (pos.size / 2) * pos.size + pos.size / 2;
            scores[0] = 0;
            return 1;
        }

        if (oppFive >= 0) {
            // 상대 4는 무조건 막는다 (탐색 안에서는 search()가 먼저 처리하므로 루트에서만 일어남)
            moves[0] = oppFive;
            scores[0] = 0;
            return 1;
        }
        if (myWin >= 0) {
            winningMove = myWin;
            return -2;
        }

        // 2차: 상대에게 큰 위협이 있으면 막는 수와 내 4만 남긴다
        if (oppMaxLevel >= L_33) {
            int k = 0;
            for (int i = 0; i < n; i++) {
                int c = moves[i];
                boolean keep = hasFourShape(me, c) || hasFourShape(opp, c);
                if (!keep && oppMaxLevel < L_WIN) {
                    // 3-3, 4-3 위협: 상대 열린3 자리, 내 열린3 반격도 허용
                    keep = hasShape(opp, c, Shape.OPEN_THREE) || hasShape(me, c, Shape.OPEN_THREE);
                }
                if (keep) {
                    moves[k] = c;
                    scores[k] = scores[i];
                    k++;
                }
            }
            if (k > 0) n = k;
        }

        sort(moves, scores, n);
        int limit = root ? 24 : ply <= 2 ? 14 : ply <= 5 ? 11 : 8;
        return Math.min(n, limit);
    }

    private boolean hasFourShape(int color, int c) {
        byte[][] s = pos.shape[color - 1];
        return Shape.isFour(s[0][c]) || Shape.isFour(s[1][c]) || Shape.isFour(s[2][c]) || Shape.isFour(s[3][c]);
    }

    private boolean hasShape(int color, int c, int shape) {
        byte[][] s = pos.shape[color - 1];
        return s[0][c] == shape || s[1][c] == shape || s[2][c] == shape || s[3][c] == shape;
    }

    private static void sort(int[] moves, int[] scores, int n) {
        for (int i = 1; i < n; i++) {
            int m = moves[i], s = scores[i];
            int j = i - 1;
            while (j >= 0 && scores[j] < s) {
                moves[j + 1] = moves[j];
                scores[j + 1] = scores[j];
                j--;
            }
            moves[j + 1] = m;
            scores[j + 1] = s;
        }
    }

    /** color가 c에 두었을 때의 가치. 위협 단계는 lastLevel에 남긴다 */
    private int evalCell(int color, int c) {
        byte[][] s = pos.shape[color - 1];
        int sum = 0, fours = 0, threes = 0;
        boolean open4 = false, over = false;
        for (int d = 0; d < 4; d++) {
            int v = s[d][c];
            if (v == Shape.FIVE) {
                lastLevel = L_FIVE;
                return WEIGHT[Shape.FIVE];
            }
            sum += WEIGHT[v];
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
        if (color == Position.BLACK && pos.rule == Rule.RENJU && (over || fours >= 2 || threes >= 2)
                && pos.isForbidden(c)) {
            lastLevel = ILLEGAL;
            return 0;
        }
        if (open4 || fours >= 2) {
            lastLevel = L_WIN;
            return sum + 20_000;
        }
        if (fours == 1 && threes >= 1) {
            lastLevel = L_43;
            return sum + 6000;
        }
        if (threes >= 2) {
            lastLevel = L_33;
            return sum + 3000;
        }
        lastLevel = fours == 1 ? L_FOUR : threes == 1 ? L_THREE : L_NONE;
        return sum;
    }

    /** 지금 둘 차례(me)의 입장에서 본 판 점수 */
    private int evaluate(int ply) {
        final int me = pos.toMove, opp = 3 - me;
        final int area = pos.area;
        final int[] board = pos.board, near = pos.near;
        int mine = 0, theirs = 0;
        int oppThreat = L_NONE;
        boolean myFourAvailable = false;
        for (int c = 0; c < area; c++) {
            if (board[c] != Position.EMPTY || near[c] == 0) continue;
            int v = evalCell(me, c);
            if (lastLevel >= L_WIN) return WIN - ply - 3;
            if (lastLevel >= L_FOUR) myFourAvailable = true;
            mine += v;
            theirs += evalCell(opp, c);
            if (lastLevel > oppThreat) oppThreat = lastLevel;
        }
        // 상대의 열린3(다음에 열린4)이 있는데 나는 4로 반격할 수 없으면 크게 불리
        int score = mine * 6 / 5 - theirs;
        if (oppThreat >= L_WIN && !myFourAvailable) score -= 4000;
        return score;
    }

    // ------------------------------------------------------------------ VCF

    private final int[] vcfLine = new int[MAX_PLY * 2];
    private int vcfLen;

    /** 시간 제한을 두고 VCF 찾기 */
    int[] findVcfWithin(int attacker, int maxFours, long ms) {
        deadline = System.currentTimeMillis() + ms;
        return findVcf(attacker, maxFours);
    }

    /** attacker 차례에 4를 연달아 두어 이기는 수순. 없으면 null */
    int[] findVcf(int attacker, int maxFours) {
        if (pos.toMove != attacker) return null;
        stopped = false;
        vcfLen = 0;
        boolean found = vcf(attacker, maxFours);
        if (!found) return null;
        return Arrays.copyOf(vcfLine, vcfLen);
    }

    private boolean vcf(int attacker, int depth) {
        if ((++nodes & 255) == 0) checkTime();
        if (stopped) return false;
        final int def = 3 - attacker;
        final int area = pos.area;
        final int[] board = pos.board, near = pos.near;
        for (int c = 0; c < area; c++) {
            if (board[c] == Position.EMPTY && near[c] > 0 && pos.makesFive(attacker, c)) {
                vcfLine[vcfLen++] = c;
                return true;
            }
        }
        for (int c = 0; c < area; c++) {
            if (board[c] == Position.EMPTY && near[c] > 0 && pos.makesFive(def, c)) return false;
        }
        if (depth <= 0) return false;
        long key = pos.hash;
        int slot = (int) (key & ((1 << VCF_BITS) - 1));
        if (vcfFailKey[slot] == key && vcfFailDepth[slot] >= depth) return false;

        // 4를 만드는 수 모으기
        int[] fours = new int[64];
        int[] prio = new int[64];
        int n = 0;
        for (int c = 0; c < area && n < 64; c++) {
            if (board[c] != Position.EMPTY || near[c] == 0) continue;
            if (!hasFourShape(attacker, c)) continue;
            int v = evalCell(attacker, c);
            if (lastLevel == ILLEGAL) continue;
            if (lastLevel >= L_WIN) {
                vcfLine[vcfLen++] = c;
                return true;
            }
            fours[n] = c;
            prio[n] = v;
            n++;
        }
        sort(fours, prio, n);
        for (int i = 0; i < n; i++) {
            int m = fours[i];
            pos.play(m);
            int block = -1;
            for (int c = 0; c < area; c++) {
                if (board[c] == Position.EMPTY && near[c] > 0 && pos.makesFive(attacker, c)) {
                    block = c;
                    break;
                }
            }
            if (block < 0) {
                pos.undo();
                continue;
            }
            if (!pos.isLegal(def, block)) {
                // 렌주룰: 흑이 막아야 할 자리가 금수 → 백 승리
                vcfLine[vcfLen++] = m;
                vcfLine[vcfLen++] = block;
                pos.undo();
                return true;
            }
            pos.play(block);
            int saved = vcfLen;
            vcfLine[vcfLen++] = m;
            vcfLine[vcfLen++] = block;
            boolean ok = vcf(attacker, depth - 1);
            pos.undo();
            pos.undo();
            if (ok) return true;
            vcfLen = saved;
            if (stopped) return false;
        }
        if (!stopped) {
            vcfFailKey[slot] = key;
            vcfFailDepth[slot] = (byte) depth;
        }
        return false;
    }

    // ------------------------------------------------------------------ 설명

    private List<Integer> fivePoints(int color) {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < pos.area; c++) {
            if (pos.board[c] == Position.EMPTY && pos.makesFive(color, c)) out.add(c);
        }
        return out;
    }

    /** 이 수가 왜 좋은지 짧게 설명 */
    String describe(int c, int me, int opp) {
        evalCell(me, c);
        int my = lastLevel;
        evalCell(opp, c);
        int their = lastLevel;
        String attack = null, defense = null;
        switch (my) {
            case L_FIVE: return "5목 완성";
            case L_WIN: attack = "열린4/4-4 공격"; break;
            case L_43: attack = "4-3 공격"; break;
            case L_33: attack = "3-3 공격"; break;
            case L_FOUR: attack = "4 만들기"; break;
            case L_THREE: attack = "열린3 만들기"; break;
            default: break;
        }
        switch (their) {
            case L_FIVE: defense = "상대 4 막기"; break;
            case L_WIN: defense = "상대 열린3 막기"; break;
            case L_43: defense = "상대 4-3 자리 막기"; break;
            case L_33: defense = "상대 3-3 자리 막기"; break;
            case L_FOUR: defense = "상대 3 견제"; break;
            case L_THREE: defense = "상대 2 견제"; break;
            default: break;
        }
        if (attack != null && defense != null) {
            return my >= their ? attack + " + 수비" : defense + " + 공격";
        }
        if (attack != null) return attack;
        if (defense != null) return defense;
        return "좋은 자리";
    }
}
