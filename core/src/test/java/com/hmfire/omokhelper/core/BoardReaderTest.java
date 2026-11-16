package com.hmfire.omokhelper.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.Test;

/** 가짜 게임 화면을 그려서 돌 인식을 확인한다 */
public class BoardReaderTest {

    private static final class Theme {
        final Color board, line, black, blackHi, white, whiteShade;

        Theme(Color board, Color line, Color black, Color blackHi, Color white, Color whiteShade) {
            this.board = board;
            this.line = line;
            this.black = black;
            this.blackHi = blackHi;
            this.white = white;
            this.whiteShade = whiteShade;
        }
    }

    private static final Theme WOOD = new Theme(new Color(222, 180, 95), new Color(70, 45, 20),
            new Color(15, 15, 15), new Color(90, 90, 90), new Color(250, 250, 248), new Color(200, 200, 195));
    private static final Theme GREEN = new Theme(new Color(70, 150, 90), new Color(20, 60, 30),
            new Color(25, 25, 30), new Color(80, 80, 90), new Color(245, 245, 245), new Color(190, 190, 190));
    private static final Theme LIGHT = new Theme(new Color(236, 214, 170), new Color(120, 100, 70),
            new Color(30, 30, 30), new Color(110, 110, 110), new Color(255, 255, 255), new Color(215, 215, 215));

    private static final Color[] RING_COLORS = {new Color(255, 40, 40), new Color(255, 150, 0), new Color(40, 120, 255)};

    private static BufferedImage render(int[] stones, int size, Theme t, int left, int top, float spacing,
                                        int lastMove, int[] rings) {
        int w = (int) (left * 2 + spacing * (size - 1)) + 1;
        int h = (int) (top * 2 + spacing * (size - 1)) + 1;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(40, 40, 50));
        g.fillRect(0, 0, w, h);
        float pad = spacing * 0.7f;
        g.setColor(t.board);
        g.fill(new java.awt.geom.Rectangle2D.Float(left - pad, top - pad, spacing * (size - 1) + 2 * pad,
                spacing * (size - 1) + 2 * pad));
        // 나뭇결 느낌의 잡음
        Random rnd = new Random(1);
        for (int i = 0; i < w * h / 40; i++) {
            int x = rnd.nextInt(w), y = rnd.nextInt(h);
            int rgb = img.getRGB(x, y);
            if ((rgb & 0xFFFFFF) == (t.board.getRGB() & 0xFFFFFF)) {
                int d = rnd.nextInt(17) - 8;
                Color c = new Color(clamp(t.board.getRed() + d), clamp(t.board.getGreen() + d), clamp(t.board.getBlue() + d));
                img.setRGB(x, y, c.getRGB());
            }
        }
        g.setColor(t.line);
        g.setStroke(new BasicStroke(Math.max(1f, spacing / 18f)));
        for (int i = 0; i < size; i++) {
            float p = i * spacing;
            g.draw(new java.awt.geom.Line2D.Float(left, top + p, left + spacing * (size - 1), top + p));
            g.draw(new java.awt.geom.Line2D.Float(left + p, top, left + p, top + spacing * (size - 1)));
        }
        // 화점
        int[] stars = size == 15 ? new int[]{3, 7, 11} : new int[]{3, 9, 15};
        for (int r : stars)
            for (int c : stars) {
                float d = spacing * 0.18f;
                g.fill(new Ellipse2D.Float(left + c * spacing - d / 2, top + r * spacing - d / 2, d, d));
            }
        float rad = spacing * 0.46f;
        for (int i = 0; i < stones.length; i++) {
            if (stones[i] == Position.EMPTY) continue;
            float cx = left + (i % size) * spacing, cy = top + (i / size) * spacing;
            boolean black = stones[i] == Position.BLACK;
            Color[] cols = black ? new Color[]{t.blackHi, t.black} : new Color[]{t.white, t.whiteShade};
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx - rad * 0.3f, cy - rad * 0.3f), rad * 1.3f,
                    new float[]{0f, 1f}, cols));
            g.fill(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
            if (!black) {
                g.setColor(new Color(150, 150, 150));
                g.setStroke(new BasicStroke(1f));
                g.draw(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
            }
            if (i == lastMove) {
                g.setColor(new Color(230, 30, 30));
                float d = spacing * 0.2f;
                g.fill(new Ellipse2D.Float(cx - d / 2, cy - d / 2, d, d));
            }
        }
        // 이 앱이 그리는 추천 고리 (가운데는 비워 둔다)
        for (int k = 0; k < rings.length; k++) {
            float cx = left + (rings[k] % size) * spacing, cy = top + (rings[k] / size) * spacing;
            float r = spacing * 0.40f;
            g.setColor(RING_COLORS[Math.min(k, 2)]);
            g.setStroke(new BasicStroke(spacing * 0.08f));
            g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        }
        g.dispose();
        return img;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static BoardReader.Pixels pixels(BufferedImage img) {
        return new BoardReader.Pixels() {
            public int width() {
                return img.getWidth();
            }

            public int height() {
                return img.getHeight();
            }

            public int rgb(int x, int y) {
                return img.getRGB(x, y) & 0xFFFFFF;
            }
        };
    }

    private static int[] randomGame(int size, int moves, Random rnd) {
        int[] b = new int[size * size];
        int placed = 0;
        while (placed < moves) {
            int c = rnd.nextInt(size * size);
            if (b[c] != 0) continue;
            b[c] = placed % 2 == 0 ? Position.BLACK : Position.WHITE;
            placed++;
        }
        return b;
    }

    private static int[] emptyRingCells(int[] b, Random rnd, int n) {
        int[] out = new int[n];
        int k = 0;
        while (k < n) {
            int c = rnd.nextInt(b.length);
            if (b[c] == 0) out[k++] = c;
        }
        return out;
    }

    private void check(int size, Theme t, float spacing, float calibError, long seed) {
        Random rnd = new Random(seed);
        int[] stones = randomGame(size, 20 + rnd.nextInt(60), rnd);
        int last = -1;
        for (int i = 0; i < stones.length; i++) if (stones[i] != 0) last = i;
        int[] rings = emptyRingCells(stones, rnd, 5);
        int left = 30, top = 120;
        BufferedImage img = render(stones, size, t, left, top, spacing, last, rings);
        float e = calibError;
        int[] read = BoardReader.read(pixels(img), left + e, top - e,
                left + spacing * (size - 1) - e, top + spacing * (size - 1) + e, size);
        assertArrayEquals("size " + size + " spacing " + spacing + " seed " + seed, stones, read);
    }

    @Test
    public void readsWoodBoards() {
        for (long seed = 0; seed < 6; seed++) {
            check(15, WOOD, 40f, 0f, seed);
            check(15, WOOD, 22f, 0f, seed);
            check(19, WOOD, 28f, 0f, seed);
        }
    }

    @Test
    public void readsOtherThemes() {
        for (long seed = 10; seed < 14; seed++) {
            check(15, GREEN, 36f, 0f, seed);
            check(15, LIGHT, 36f, 0f, seed);
            check(19, LIGHT, 24f, 0f, seed);
        }
    }

    @Test
    public void toleratesSmallCalibrationError() {
        for (long seed = 20; seed < 24; seed++) {
            check(15, WOOD, 40f, 3f, seed);
            check(19, GREEN, 30f, 2f, seed);
        }
    }

    @Test
    public void emptyBoardWithRingsIsEmpty() {
        int[] empty = new int[15 * 15];
        BufferedImage img = render(empty, 15, WOOD, 30, 120, 36f, -1, new int[]{112, 113, 97, 0, 224});
        int[] read = BoardReader.read(pixels(img), 30, 120, 30 + 36 * 14, 120 + 36 * 14, 15);
        assertArrayEquals(empty, read);
    }

    @Test
    public void plausibility() {
        int[] b = new int[9];
        b[0] = Position.BLACK;
        assertEquals(true, BoardReader.plausible(b));
        b[1] = Position.WHITE;
        b[2] = Position.WHITE;
        b[3] = Position.WHITE;
        assertEquals(false, BoardReader.plausible(b));
    }
}
