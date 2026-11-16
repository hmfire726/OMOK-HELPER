package com.hmfire.omokhelper.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShapeTest {
    /** 11칸 문자열(가운데 5번이 놓을 칸 'X')로 모양 찾기. X=내 돌, O=막힘, .=빈칸 */
    private static int shape(String line, boolean longWins) {
        assertEquals(11, line.length());
        assertEquals('X', line.charAt(5));
        int code = 0;
        for (int i = 0; i < 10; i++) {
            char ch = line.charAt(Shape.lineIndex(i));
            int v = ch == 'X' ? 1 : ch == 'O' ? 2 : 0;
            code += v * Shape.POW3[i];
        }
        return (longWins ? Shape.LONG_WINS : Shape.EXACT_FIVE)[code];
    }

    private static int shape(String line) {
        return shape(line, true);
    }

    @Test
    public void fives() {
        assertEquals(Shape.FIVE, shape("...XXXXX..."));
        assertEquals(Shape.FIVE, shape(".XXXXX....."));
        assertEquals(Shape.FIVE, shape("..XXXXXX...", true));
        assertEquals(Shape.OVERLINE, shape("..XXXXXX...", false));
    }

    @Test
    public void fours() {
        assertEquals(Shape.OPEN_FOUR, shape("...XXXX...."));
        assertEquals(Shape.OPEN_FOUR, shape("....XXXX..."));
        assertEquals(Shape.FOUR, shape("..OXXXX...."));
        assertEquals(Shape.FOUR, shape("..XX.XX...."));
        assertEquals(Shape.FOUR, shape("...XXX.X..."));
        assertEquals(Shape.DOUBLE_FOUR, shape(".X.XXX.X..."));
    }

    @Test
    public void exactFiveRuleRemovesOverlineFour() {
        // X.XXXX : 가운데 빈칸에 두면 6목 → 렌주 흑에게는 오른쪽 끝 하나만 5목 자리
        assertEquals(Shape.OPEN_FOUR, shape("..X.XXXX...", true));
        assertEquals(Shape.FOUR, shape("..X.XXXX...", false));
        assertEquals(Shape.FOUR, shape("..X.XXX.O..", false));
    }

    @Test
    public void threes() {
        assertEquals(Shape.OPEN_THREE, shape("....XXX...."));
        assertEquals(Shape.OPEN_THREE, shape("...X.XX...."));
        assertEquals(Shape.THREE, shape("...OXXX...."));
        assertEquals(Shape.THREE, shape("..OX.XX...."));
    }

    @Test
    public void twosAndDeadLines() {
        assertEquals(Shape.OPEN_TWO, shape("....XX....."));
        assertEquals(Shape.ONE, shape(".....X....."));
        // 양쪽이 막혀 5칸이 안 되면 아무 모양도 아님
        assertEquals(Shape.NONE, shape("...OXXX.O.."));
        assertEquals(Shape.NONE, shape("....OXO...."));
    }
}
