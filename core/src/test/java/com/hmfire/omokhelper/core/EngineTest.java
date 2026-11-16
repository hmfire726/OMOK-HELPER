package com.hmfire.omokhelper.core;

import static com.hmfire.omokhelper.core.TestBoards.cell;
import static com.hmfire.omokhelper.core.TestBoards.position;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class EngineTest {
    private static final int B = Position.BLACK, W = Position.WHITE;

    private static Analysis analyze(Position p, long ms, int topN) {
        return new Engine(p).analyze(ms, topN);
    }

    @Test
    public void thinkTimeOptionsIncludeFourAndFiveSeconds() {
        assertTrue(Arrays.toString(Engine.THINK_TIMES_MS),
                Arrays.stream(Engine.THINK_TIMES_MS).anyMatch(t -> t == 4000));
        assertTrue(Arrays.stream(Engine.THINK_TIMES_MS).anyMatch(t -> t == 5000));
        assertEquals(5, Engine.THINK_TIMES_MS.length);
    }

    @Test
    public void firstMoveIsCenter() {
        Position p = new Position(15, Rule.FREESTYLE);
        assertEquals(cell("H8"), analyze(p, 300, 3).best().cell);
        Position p19 = new Position(19, Rule.FREESTYLE);
        assertEquals(9 * 19 + 9, analyze(p19, 300, 3).best().cell);
    }

    @Test
    public void completesFive() {
        Position p = position(Rule.FREESTYLE, B, "H8 I8 J8 K8", "H9 I9 J9 A1");
        Analysis a = analyze(p, 500, 3);
        assertTrue(a.best().winning);
        assertTrue(a.best().cell == cell("G8") || a.best().cell == cell("L8"));
    }

    @Test
    public void blocksFour() {
        Position p = position(Rule.FREESTYLE, W, "H8 I8 J8 K8", "G8 H9 I10");
        Analysis a = analyze(p, 500, 3);
        assertEquals(cell("L8"), a.best().cell);
    }

    @Test
    public void defendsOpenThree() {
        Position p = position(Rule.FREESTYLE, W, "H8 I8 J8", "H9 I10");
        Analysis a = analyze(p, 1500, 3);
        Set<Integer> ok = new HashSet<>(Arrays.asList(cell("F8"), cell("G8"), cell("K8"), cell("L8")));
        assertTrue("got " + Coords.name(a.best().cell, 15), ok.contains(a.best().cell));
    }

    @Test
    public void findsDoubleFourInFreestyle() {
        Position p = position(Rule.FREESTYLE, B, "H8 I8 J8 L9 L10 L11", "G8 L12 A1 A15 O1 O15");
        Analysis a = analyze(p, 1000, 3);
        assertEquals(cell("L8"), a.best().cell);
        assertTrue(a.best().winning);
    }

    @Test
    public void renjuForbidsBlackDoubleFour() {
        Position p = position(Rule.RENJU, B, "H8 I8 J8 L9 L10 L11", "G8 L12 A1 A15 O1 O15");
        assertTrue(p.isForbidden(cell("L8")));
        Analysis a = analyze(p, 1000, 5);
        for (Analysis.Suggestion s : a.moves) assertNotEquals(cell("L8"), s.cell);
    }

    @Test
    public void renjuDoubleThreeAndOverline() {
        Position p = position(Rule.RENJU, B, "H8 I8 J9 J10", "A1 A2 A3 A4");
        assertTrue("3-3 금수", p.isForbidden(cell("J8")));
        Position free = position(Rule.FREESTYLE, B, "H8 I8 J9 J10", "A1 A2 A3 A4");
        assertFalse(free.isForbidden(cell("J8")));

        Position over = position(Rule.RENJU, B, "H8 I8 J8 L8 M8", "A1 C1 E1 G1 J1");
        assertTrue("장목 금수", over.isForbidden(cell("K8")));
        assertFalse(over.makesFive(B, cell("K8")));
        // 백은 장목도 승리
        Position whiteLong = position(Rule.RENJU, W, "A1 C1 E1 G1 J1 L1", "H8 I8 J8 L8 M8");
        assertTrue(whiteLong.makesFive(W, cell("K8")));
    }

    @Test
    public void renjuAllowsFourThree() {
        Position p = position(Rule.RENJU, B, "H8 I8 J8 K9 K10", "G8 A1 A2 A3 A4");
        assertFalse(p.isForbidden(cell("K8")));
    }

    @Test
    public void renjuFakeThreeIsNotCounted() {
        // 가로 H8 I8 + J8 은 열린3이지만, 세로 J9 J10 쪽은 J11이 백으로 막혀 있고 J7 너머도 막혀
        // 열린4를 만들 수 없으므로 3-3이 아님
        Position p = position(Rule.RENJU, B, "H8 I8 J9 J10", "J11 J6 A1 A2");
        assertFalse(p.isForbidden(cell("J8")));
    }

    @Test
    public void winnerDetection() {
        assertEquals(B, position(Rule.FREESTYLE, W, "D4 E5 F6 G7 H8", "A1 A2 A3 A4").winner());
        assertEquals(Position.EMPTY, position(Rule.RENJU, W, "H8 I8 J8 K8 L8 M8", "A1 C1 E1 G1 J1").winner());
        assertEquals(B, position(Rule.FREESTYLE, W, "H8 I8 J8 K8 L8 M8", "A1 C1 E1 G1 J1").winner());
        Analysis a = analyze(position(Rule.FREESTYLE, W, "D4 E5 F6 G7 H8", "A1 A2 A3 A4"), 300, 3);
        assertEquals(B, a.winner);
        assertTrue(a.moves.isEmpty());
    }

    @Test
    public void suggestionsAreDistinctLegalAndRanked() {
        Position p = position(Rule.RENJU, B, "H8 I9 G9 J7", "I8 H9 G10 I7");
        Analysis a = analyze(p, 800, 5);
        assertEquals(5, a.moves.size());
        Set<Integer> seen = new HashSet<>();
        int prev = Integer.MAX_VALUE;
        for (Analysis.Suggestion s : a.moves) {
            assertEquals(Position.EMPTY, p.get(s.cell));
            assertTrue(p.isLegal(B, s.cell));
            assertTrue(seen.add(s.cell));
            assertTrue(s.score <= prev);
            prev = s.score;
            assertFalse(s.reason.isEmpty());
        }
        assertTrue(a.depth >= 2);
        assertEquals(a.best().cell, a.line[0]);
    }

    @Test
    public void respectsFourAndFiveSecondThinkTime() {
        for (int ms : new int[]{4000, 5000}) {
            Position p = position(Rule.RENJU, W, "H8 I9 G9 J7 K10", "I8 H9 G10 I7");
            long t0 = System.currentTimeMillis();
            Analysis a = analyze(p, ms, 3);
            long took = System.currentTimeMillis() - t0;
            assertNotNull(a.best());
            assertTrue("took " + took + "ms for " + ms, took <= ms + 700);
            assertTrue(a.depth >= 3);
        }
    }

    @Test
    public void cancelStopsQuickly() throws Exception {
        Position p = position(Rule.FREESTYLE, W, "H8 I9 G9 J7 K10", "I8 H9 G10 I7");
        Engine e = new Engine(p);
        AtomicReference<Analysis> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(e.analyze(5000, 3)));
        long t0 = System.currentTimeMillis();
        t.start();
        Thread.sleep(150);
        e.cancel();
        t.join(3000);
        assertFalse(t.isAlive());
        assertTrue(System.currentTimeMillis() - t0 < 1500);
    }

    @Test
    public void vcfLinesAreValid() {
        Random rnd = new Random(7);
        int found = 0;
        for (int trial = 0; trial < 300; trial++) {
            Rule rule = trial % 2 == 0 ? Rule.FREESTYLE : Rule.RENJU;
            int[] b = new int[225];
            int stones = 14 + rnd.nextInt(26);
            for (int i = 0; i < stones; i++) {
                int r = 3 + rnd.nextInt(9), c = 3 + rnd.nextInt(9);
                if (b[r * 15 + c] == 0) b[r * 15 + c] = i % 2 == 0 ? B : W;
            }
            int toMove = Position.guessToMove(b);
            Position p = new Position(15, rule, b, toMove);
            if (p.winner() != Position.EMPTY) continue;
            int attacker = p.toMove(), defender = 3 - attacker;
            // 상대가 이미 4를 가진 판은 VCF 대상이 아님
            boolean defenderFour = false;
            for (int c = 0; c < 225; c++) if (p.get(c) == 0 && p.makesFive(defender, c)) defenderFour = true;
            if (defenderFour) continue;

            int[] line = new Engine(p).findVcfWithin(attacker, 20, 500);
            if (line == null) continue;
            found++;
            for (int i = 0; i < line.length; i++) {
                int m = line[i];
                if (i % 2 == 0) {
                    assertTrue(p.isLegal(attacker, m));
                    p.play(m);
                } else {
                    // 수비는 반드시 그 5목 자리를 막는 수여야 한다
                    int fives = 0;
                    for (int c = 0; c < 225; c++) if (p.get(c) == 0 && p.makesFive(attacker, c)) fives++;
                    assertEquals(1, fives);
                    assertTrue(p.makesFive(attacker, m));
                    if (!p.isLegal(defender, m)) {
                        assertEquals("forbidden block ends the line", line.length - 1, i);
                        break;
                    }
                    p.play(m);
                }
            }
            if (line.length % 2 == 1) {
                // 마지막 공격수 뒤: 이미 5목이거나, 5목 자리가 두 곳 이상
                int fives = 0;
                for (int c = 0; c < 225; c++) if (p.get(c) == 0 && p.makesFive(attacker, c)) fives++;
                assertTrue(p.winner() == attacker || fives >= 2);
            }
        }
        assertTrue("found " + found, found >= 5);
    }
}
