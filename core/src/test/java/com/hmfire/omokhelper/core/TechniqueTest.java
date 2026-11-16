package com.hmfire.omokhelper.core;

import static com.hmfire.omokhelper.core.TestBoards.cell;
import static com.hmfire.omokhelper.core.TestBoards.position;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/** 오목 기술: VCT, 3-3, 4-3, 금수 유도, 상대 필승 수순 차단 */
public class TechniqueTest {
    private static final int B = Position.BLACK, W = Position.WHITE;

    @Test
    public void doubleThreeWinsByVct() {
        // F8 G8 (가로 2) + H9 H10 (세로 2) → H8 이 3-3
        Position p = position(Rule.FREESTYLE, B, "F8 G8 H9 H10", "A1 C1 A3 C3");
        int[] line = new Engine(p).findVctWithin(B, 6, 2000);
        assertNotNull(line);
        assertEquals(cell("H8"), line[0]);

        Analysis a = new Engine(p).analyze(1000, 3);
        assertEquals(cell("H8"), a.best().cell);
        assertTrue(a.best().winning);
        assertTrue(a.best().reason, a.best().reason.contains("3-3"));
        assertTrue(a.best().reason, a.best().reason.contains("VCT"));
    }

    @Test
    public void renjuBlackCannotUseDoubleThreeButWhiteCan() {
        Position black = position(Rule.RENJU, B, "F8 G8 H9 H10", "A1 C1 A3 C3");
        assertTrue(black.isForbidden(cell("H8")));
        int[] line = new Engine(black).findVctWithin(B, 6, 1500);
        if (line != null) assertNotEquals(cell("H8"), line[0]);

        Position white = position(Rule.RENJU, W, "A1 C1 A3 C3 E1", "F8 G8 H9 H10");
        int[] wl = new Engine(white).findVctWithin(W, 6, 2000);
        assertNotNull(wl);
        assertEquals(cell("H8"), wl[0]);
    }

    @Test
    public void fourThreeWins() {
        // 가로: G8 H8 I8 (F8 은 백) → J8 이면 막힌 4.  세로: J9 J10 → J8 이면 열린3.  J8 = 4-3
        Position p = position(Rule.RENJU, B, "G8 H8 I8 J9 J10", "F8 A1 C1 A3 C3");
        assertEquals(Threats.L_43, Threats.level(p, B, cell("J8")));
        Analysis a = new Engine(p).analyze(1000, 3);
        assertTrue(a.best().winning);
        assertTrue(a.best().reason, a.best().reason.contains("4-3"));
    }

    @Test
    public void whiteWinsByForbiddenPointTrap() {
        // 흑 H8 자리는 3-3 금수. 백이 L4 로 4를 만들면 흑이 막을 자리가 H8 → 막을 수 없다
        Position p = position(Rule.RENJU, W, "F8 G8 H9 H10 M3 G9", "I7 J6 K5 A1 C1");
        assertTrue(p.isForbidden(cell("H8")));
        Engine e = new Engine(p);
        int[] line = e.findVcfWithin(W, 20, 1000);
        assertNotNull(line);
        assertEquals(cell("L4"), line[0]);
        assertTrue(e.lastThreatUsedForbidden());

        Analysis a = new Engine(position(Rule.RENJU, W, "F8 G8 H9 H10 M3 G9", "I7 J6 K5 A1 C1")).analyze(1000, 3);
        assertEquals(cell("L4"), a.best().cell);
        assertTrue(a.best().reason, a.best().reason.contains("금수 유도"));
    }

    @Test
    public void blocksOpponentDoubleThreeSetup() {
        // 백이 H8 에 3-3 을 놓을 준비가 되어 있다. 주변 61칸 중 백의 필승을 막는 자리는 E8, H8 뿐이다.
        String black = "I10 G10 E9 J7", white = "F8 G8 H9 H10";
        Position p = position(Rule.FREESTYLE, B, black, white);
        Analysis a = new Engine(p).analyze(1500, 3);
        int best = a.best().cell;
        assertTrue("흑 " + Coords.name(best, 15), best == cell("E8") || best == cell("H8"));
        assertTrue(a.best().reason, a.best().reason.contains("차단"));
        Position after = position(Rule.FREESTYLE, B, black, white);
        after.play(best);
        Engine check = new Engine(after);
        assertNull(check.findVcfWithin(W, 30, 2000));
        assertNull(check.findVctWithin(W, 8, 4000));
        assertFalse(check.timedOut());
    }

    /**
     * VCT 첫 수가 열린3이면, 상대의 "모든" 응수(주변 빈칸 전부)에 대해 공격자가 여전히 이기는지 확인한다.
     * 엔진이 고려하는 방어 후보에 빠진 수가 있으면 여기서 걸린다.
     */
    @Test
    public void vctSurvivesEveryDefence() {
        Random rnd = new Random(11);
        int checked = 0;
        for (int trial = 0; trial < 400 && checked < 8; trial++) {
            Rule rule = trial % 2 == 0 ? Rule.FREESTYLE : Rule.RENJU;
            int[] b = new int[225];
            int stones = 8 + rnd.nextInt(14);
            for (int i = 0; i < stones; i++) {
                int r = 4 + rnd.nextInt(7), c = 4 + rnd.nextInt(7);
                if (b[r * 15 + c] == 0) b[r * 15 + c] = i % 2 == 0 ? B : W;
            }
            Position p = new Position(15, rule, b, Position.guessToMove(b));
            if (p.winner() != Position.EMPTY) continue;
            int att = p.toMove(), def = 3 - att;
            if (Threats.fivePoint(p, def, null) >= 0 || Threats.fivePoint(p, att, null) >= 0) continue;
            Engine e = new Engine(p);
            if (e.findVcfWithin(att, 30, 300) != null) continue; // 4만으로 이기는 판은 VCF 테스트에서 확인
            int[] line = e.findVctWithin(att, 5, 600);
            if (line == null) continue;
            int m = line[0];
            p.play(m);
            if (Threats.fivePoint(p, att, null) >= 0) {
                p.undo();
                continue; // 4로 시작하면 응수가 하나뿐
            }
            checked++;
            int inconclusive = 0;
            for (int d = 0; d < 225; d++) {
                if (p.get(d) != 0 || p.near[d] == 0 || !p.isLegal(def, d)) continue;
                p.play(d);
                assertNotEquals("방어하다 5목", def, p.winner());
                boolean wins = Threats.fivePoint(p, att, null) >= 0
                        || Threats.hasWinThreat(p, att) && Threats.fivePoint(p, def, null) < 0
                        || e.findVcfWithin(att, 30, 1500) != null;
                boolean timedOut = false;
                if (!wins) {
                    wins = e.findVctWithin(att, 8, 3000) != null;
                    timedOut = e.timedOut();
                }
                if (!wins && timedOut) inconclusive++;
                assertTrue(rule + " VCT " + Coords.line(line, 15, 12) + " 가 " + Coords.name(d, 15)
                        + " 응수에 막힘", wins || timedOut);
                p.undo();
            }
            assertTrue(inconclusive < 5);
            p.undo();
        }
        assertTrue("checked " + checked, checked >= 3);
    }
}
