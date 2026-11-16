package com.hmfire.omokhelper.core;

import java.util.Arrays;

/**
 * 위협 탐색 — 상대가 매번 막을 수밖에 없는 수만 이어서 이기는 길을 찾는다.
 *
 * <ul>
 *   <li><b>VCF</b> (Victory by Continuous Fours): 4만 연달아 두기. 상대의 응수는 5목 자리 막기 하나뿐.</li>
 *   <li><b>VCT</b> (Victory by Continuous Threats): 4와 열린3(3-3, 4-3 포함)을 섞어 두기.
 *       열린3에 대해서는 상대의 모든 방어(양 끝 막기, 띈 자리 막기, 4로 반격)를 하나씩 확인해서
 *       전부 이겨야 필승으로 인정한다.</li>
 *   <li><b>금수 유도</b> (렌주룰): 흑이 막아야 할 자리가 금수(3-3/4-4/장목)이면 흑은 막을 수 없다.</li>
 * </ul>
 */
final class ThreatSearch {
    private static final int MAX_PLY = 160;
    /** VCT에서 한 국면에 검토할 열린3 수 최대 개수 */
    private static final int THREE_LIMIT = 10;
    /** VCT 깊이가 다 됐을 때 덧붙여 보는 VCF 길이 */
    private static final int VCF_TAIL = 10;

    private static final long KEY_VCF = 0x5DEECE66DL * 0x9E3779B97F4A7C15L;
    private static final long KEY_VCT = 0xC2B2AE3D27D4EB4FL;
    private static final long KEY_BLACK = 0x165667B19E3779F9L;
    private static final long KEY_WHITE = 0x27D4EB2F165667C5L;

    private final Position pos;
    private final Clock clock;
    private int attacker;

    private final int[][] line = new int[MAX_PLY + 2][MAX_PLY + 2];
    private final int[] len = new int[MAX_PLY + 2];
    private final boolean[] trap = new boolean[MAX_PLY + 2];

    // 결과 기억: 이긴 결과는 그 깊이 이상에서, 진 결과는 그 깊이 이하에서 그대로 쓸 수 있다
    private static final int TT_BITS = 17;
    private final long[] ttKey = new long[1 << TT_BITS];
    private final byte[] ttDepth = new byte[1 << TT_BITS];
    private final byte[] ttWin = new byte[1 << TT_BITS];

    /** 마지막으로 찾은 수순이 흑의 금수를 이용하는가 */
    boolean lastUsedForbidden;

    ThreatSearch(Position pos, Clock clock) {
        this.pos = pos;
        this.clock = clock;
    }

    /** attacker 차례에서 연속 4로 이기는 수순 (없거나 시간이 다 되면 null) */
    int[] vcf(int attacker, int maxFours) {
        if (pos.toMove != attacker) return null;
        this.attacker = attacker;
        boolean ok = attack(maxFours, 0, true);
        return ok ? result() : null;
    }

    /** attacker 차례에서 연속 3·4로 이기는 수순. 짧은 수순부터 찾는다. */
    int[] vct(int attacker, int maxThreats) {
        if (pos.toMove != attacker) return null;
        this.attacker = attacker;
        for (int depth = 2; depth <= maxThreats; depth++) {
            if (attack(depth, 0, false)) return result();
            if (clock.stopped) break;
        }
        return null;
    }

    private int[] result() {
        lastUsedForbidden = trap[0];
        return Arrays.copyOf(line[0], len[0]);
    }

    private long key(boolean foursOnly) {
        return pos.hash ^ (foursOnly ? KEY_VCF : KEY_VCT) ^ (attacker == Position.BLACK ? KEY_BLACK : KEY_WHITE);
    }

    /** 0 = 모름, 1 = 이김, -1 = 못 이김 */
    private int probe(long key, int depth) {
        int slot = (int) (key & ((1 << TT_BITS) - 1));
        if (ttKey[slot] != key) return 0;
        if (ttWin[slot] == 1 && ttDepth[slot] <= depth) return 1;
        if (ttWin[slot] == 0 && ttDepth[slot] >= depth) return -1;
        return 0;
    }

    private void store(long key, int depth, boolean win) {
        if (clock.stopped) return;
        int slot = (int) (key & ((1 << TT_BITS) - 1));
        ttKey[slot] = key;
        ttDepth[slot] = (byte) Math.min(depth, 127);
        ttWin[slot] = (byte) (win ? 1 : 0);
    }

    private void set(int ply, int move) {
        line[ply][0] = move;
        int n = Math.min(len[ply + 1], MAX_PLY);
        System.arraycopy(line[ply + 1], 0, line[ply], 1, n);
        len[ply] = n + 1;
        trap[ply] = trap[ply + 1];
    }

    private void single(int ply, int move) {
        line[ply][0] = move;
        len[ply] = 1;
        trap[ply] = false;
    }

    // ------------------------------------------------------------------ 공격 (attacker 차례)

    private boolean attack(int depth, int ply, boolean foursOnly) {
        len[ply] = 0;
        trap[ply] = false;
        if (clock.tick() || ply >= MAX_PLY - 2) return false;
        final int att = attacker, def = 3 - att;
        final int[] count = new int[1];

        int five = Threats.fivePoint(pos, att, null);
        if (five >= 0) {
            single(ply, five);
            return true;
        }
        int defFive = Threats.fivePoint(pos, def, count);
        if (count[0] >= 2) return false;
        if (count[0] == 1) {
            // 상대 4를 먼저 막아야 한다. 막는 수가 다시 위협이 되면 계속 공격할 수 있다.
            if (!pos.isLegal(att, defFive)) return false;
            pos.play(defFive);
            boolean ok = defend(depth, ply + 1, foursOnly);
            pos.undo();
            if (ok) set(ply, defFive);
            return ok;
        }
        if (depth <= 0) {
            return !foursOnly && attack(VCF_TAIL, ply, true);
        }
        long key = key(foursOnly);
        int known = probe(key, depth);
        if (known != 0 && ply > 0) return known > 0;

        final int area = pos.area;
        final int[] board = pos.board, near = pos.near;
        int[] fours = new int[48];
        int[] fourPrio = new int[48];
        int nf = 0;
        int[] threes = new int[64];
        int[] threePrio = new int[64];
        int nt = 0;
        for (int c = 0; c < area; c++) {
            if (board[c] != Position.EMPTY || near[c] == 0) continue;
            int lv = Threats.level(pos, att, c);
            if (lv == Threats.ILLEGAL || lv == Threats.L_NONE) continue;
            if (lv >= Threats.L_WIN) {
                // 열린4 / 4-4: 상대가 막을 수 없다
                single(ply, c);
                store(key, depth, true);
                return true;
            }
            int prio = lv * 100_000 + Threats.value(pos, att, c) + Threats.value(pos, def, c) / 2;
            if (Threats.hasFour(pos, att, c)) {
                if (nf < fours.length) {
                    fours[nf] = c;
                    fourPrio[nf++] = prio;
                }
            } else if (!foursOnly && nt < threes.length) {
                threes[nt] = c;
                threePrio[nt++] = prio;
            }
        }
        // 상대가 이미 열린3(다음 수에 열린4)을 가지고 있으면 내 3은 느리다 → 4만 쓴다
        if (nt > 0 && Threats.hasWinThreat(pos, def)) nt = 0;

        sort(fours, fourPrio, nf);
        sort(threes, threePrio, nt);
        nt = Math.min(nt, THREE_LIMIT);

        for (int i = 0; i < nf + nt; i++) {
            int m = i < nf ? fours[i] : threes[i - nf];
            pos.play(m);
            boolean ok = defend(depth - 1, ply + 1, foursOnly);
            pos.undo();
            if (ok) {
                set(ply, m);
                store(key, depth, true);
                return true;
            }
            if (clock.stopped) return false;
        }
        store(key, depth, false);
        return false;
    }

    // ------------------------------------------------------------------ 수비 (상대 차례)

    private boolean defend(int depth, int ply, boolean foursOnly) {
        len[ply] = 0;
        trap[ply] = false;
        if (clock.tick() || ply >= MAX_PLY - 2) return false;
        final int att = attacker, def = 3 - att;
        final int[] count = new int[1];

        if (Threats.fivePoint(pos, def, null) >= 0) return false;
        int five = Threats.fivePoint(pos, att, count);
        if (count[0] >= 2) {
            // 5목 자리가 두 곳 — 하나만 막을 수 있다
            single(ply, five);
            return true;
        }
        if (count[0] == 1) {
            if (!pos.isLegal(def, five)) {
                // 렌주룰: 흑이 막아야 할 자리가 금수
                single(ply, five);
                trap[ply] = true;
                return true;
            }
            pos.play(five);
            boolean ok = attack(depth, ply + 1, foursOnly);
            pos.undo();
            if (ok) set(ply, five);
            return ok;
        }
        if (foursOnly) return false;
        // 공격자가 다음 수에 열린4/4-4를 만들 수 있어야 위협이다
        if (!Threats.hasWinThreat(pos, att)) return false;

        long key = key(false);
        int known = probe(key, depth);
        if (known != 0) return known > 0;

        // 방어 후보: 공격자가 4를 만들 수 있는 모든 자리(열린3의 양 끝·띈 자리 포함) + 나의 4 반격
        final int area = pos.area;
        final int[] board = pos.board, near = pos.near;
        int[] moves = new int[96];
        int[] prio = new int[96];
        int n = 0;
        for (int c = 0; c < area && n < moves.length; c++) {
            if (board[c] != Position.EMPTY || near[c] == 0) continue;
            boolean counter = Threats.hasFour(pos, def, c);
            if (!counter && !Threats.hasFour(pos, att, c)) continue;
            if (!pos.isLegal(def, c)) continue;
            moves[n] = c;
            prio[n++] = (counter ? 1_000_000 : 0) + Threats.value(pos, att, c) + Threats.value(pos, def, c);
        }
        sort(moves, prio, n);
        boolean recorded = false;
        for (int i = 0; i < n; i++) {
            pos.play(moves[i]);
            boolean ok = attack(depth, ply + 1, false);
            pos.undo();
            if (!ok) {
                store(key, depth, false);
                return false;
            }
            if (!recorded) {
                set(ply, moves[i]);
                recorded = true;
            }
        }
        if (n == 0) {
            // 둘 수 있는 방어가 없다 (모두 금수 등)
            trap[ply] = def == Position.BLACK && pos.rule == Rule.RENJU;
        }
        store(key, depth, true);
        return true;
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
}
