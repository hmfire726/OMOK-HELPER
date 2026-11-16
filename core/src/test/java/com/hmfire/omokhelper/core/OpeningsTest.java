package com.hmfire.omokhelper.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class OpeningsTest {
    private static String name(String black, String white) {
        return Openings.name(TestBoards.board(15, black, white), 15);
    }

    @Test
    public void knownOpenings() {
        assertEquals("화월(花月) · 직접주", name("H8 I9", "H9"));
        assertEquals("한성(寒星) · 직접주", name("H8 H10", "H9"));
        assertEquals("유성(遊星) · 직접주", name("H8 J6", "H9"));
        assertEquals("운월(雲月) · 간접주", name("H8 I8", "I9"));
        assertEquals("포월(浦月) · 간접주", name("H8 I7", "I9"));
        assertEquals("장성(長星) · 간접주", name("H8 J10", "I9"));
        assertEquals("혜성(彗星) · 간접주", name("H8 F6", "I9"));
    }

    @Test
    public void rotationsAndMirrors() {
        assertEquals("화월(花月) · 직접주", name("H8 G9", "G8"));
        assertEquals("화월(花月) · 직접주", name("H8 G9", "H9"));
        assertEquals("화월(花月) · 직접주", name("H8 G7", "H7"));
        assertEquals("포월(浦月) · 간접주", name("H8 G9", "I9"));
        assertEquals("운월(雲月) · 간접주", name("H8 G8", "G7"));
        // 천원이 아니어도 모양이 같으면
        assertEquals("화월(花月) · 직접주", name("D4 E5", "D5"));
    }

    @Test
    public void allTwentySixAreDistinctAndReachable() {
        Set<String> seen = new HashSet<>();
        String[] cols = {"F", "G", "H", "I", "J"};
        for (String white : new String[]{"H9", "I9"}) {
            for (String c : cols) {
                for (int r = 6; r <= 10; r++) {
                    String third = c + r;
                    if (third.equals("H8") || third.equals(white)) continue;
                    String n = name("H8 " + third, white);
                    if (n != null) seen.add(n);
                }
            }
        }
        assertEquals(26, seen.size());
    }

    @Test
    public void notAnOpening() {
        assertNull(name("H8 L12", "H9"));
        assertNull(name("H8 I9 A1", "H9"));
        assertNull(name("H8 I9", "K11"));
        assertNull(name("H8", "H9"));
    }
}
