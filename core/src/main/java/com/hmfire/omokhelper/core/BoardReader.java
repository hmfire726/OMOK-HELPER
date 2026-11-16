package com.hmfire.omokhelper.core;

import java.util.Arrays;

/**
 * 화면 이미지에서 돌 읽기.
 *
 * 사용자가 맞춘 두 모서리(맨 왼쪽 위 교차점, 맨 오른쪽 아래 교차점)로 모든 교차점 위치를 계산하고,
 * 교차점 주변을 대각선 방향으로만 샘플링한다. (가로/세로 선과 화점은 피하고, 돌 위의 표시는 중앙값으로 무시)
 * 칸 한가운데(선이 지나지 않는 곳)의 색을 바둑판 색으로 삼아 비교한다.
 */
public final class BoardReader {
    private BoardReader() {}

    /** 이미지 픽셀 접근. rgb는 0xRRGGBB */
    public interface Pixels {
        int width();

        int height();

        int rgb(int x, int y);
    }

    /** 교차점 간격 대비 샘플 위치 (dx, dy) */
    private static final float[] SAMPLE_DX, SAMPLE_DY;
    static {
        float[] radii = {0.14f, 0.21f, 0.28f};
        int[] baseAngles = {45, 135, 225, 315};
        int[] spread = {-14, 0, 14};
        int n = radii.length * baseAngles.length * spread.length;
        SAMPLE_DX = new float[n];
        SAMPLE_DY = new float[n];
        int k = 0;
        for (float r : radii)
            for (int a : baseAngles)
                for (int s : spread) {
                    double rad = Math.toRadians(a + s);
                    SAMPLE_DX[k] = (float) (r * Math.cos(rad));
                    SAMPLE_DY[k] = (float) (r * Math.sin(rad));
                    k++;
                }
    }

    /** 돌이 가려서는 안 되는 안쪽 반지름 (간격 대비). 추천 고리는 이보다 바깥에 그려야 한다. */
    public static final float SAMPLE_RADIUS = 0.30f;

    /** 판 상태와 함께 판단 근거를 담는다 */
    public static final class Result {
        public final int[] stones;
        public final int backgroundLuma;
        public final int backgroundSat;

        Result(int[] stones, int bgL, int bgS) {
            this.stones = stones;
            this.backgroundLuma = bgL;
            this.backgroundSat = bgS;
        }
    }

    public static int[] read(Pixels px, float x0, float y0, float x1, float y1, int size) {
        return readDetailed(px, x0, y0, x1, y1, size).stones;
    }

    public static Result readDetailed(Pixels px, float x0, float y0, float x1, float y1, int size) {
        float sx = (x1 - x0) / (size - 1);
        float sy = (y1 - y0) / (size - 1);

        // 1) 바둑판 색: 칸 가운데들의 중앙값
        int squares = (size - 1) * (size - 1);
        int[] bgLs = new int[squares];
        int[] bgSs = new int[squares];
        int k = 0;
        for (int r = 0; r < size - 1; r++) {
            for (int c = 0; c < size - 1; c++) {
                int rgb = averageRgb(px, x0 + (c + 0.5f) * sx, y0 + (r + 0.5f) * sy);
                bgLs[k] = luma(rgb);
                bgSs[k] = sat(rgb);
                k++;
            }
        }
        int bgL = median(bgLs, k);
        int bgS = median(bgSs, k);

        // 2) 교차점마다 샘플의 밝기/채도 중앙값으로 판단
        int[] stones = new int[size * size];
        int n = SAMPLE_DX.length;
        int[] ls = new int[n];
        int[] ss = new int[n];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                float cx = x0 + c * sx, cy = y0 + r * sy;
                int m = 0;
                for (int i = 0; i < n; i++) {
                    int x = Math.round(cx + SAMPLE_DX[i] * sx);
                    int y = Math.round(cy + SAMPLE_DY[i] * sy);
                    if (x < 0 || y < 0 || x >= px.width() || y >= px.height()) continue;
                    int rgb = px.rgb(x, y);
                    ls[m] = luma(rgb);
                    ss[m] = sat(rgb);
                    m++;
                }
                if (m < n / 2) continue;
                stones[r * size + c] = classify(median(ls, m), median(ss, m), bgL, bgS);
            }
        }
        return new Result(stones, bgL, bgS);
    }

    static int classify(int l, int s, int bgL, int bgS) {
        // 흑돌: 판보다 확실히 어둡다
        if (l < bgL * 0.58f && l < bgL - 45) return Position.BLACK;
        // 백돌: 판보다 밝고 색이 거의 없다
        if (l > bgL + 26 && s < 70) return Position.WHITE;
        // 색이 있는 판(나무색, 초록색 등) 위의 회색빛 백돌
        if (bgS >= 40 && s < bgS * 0.45f && l > bgL * 0.82f && l > 110) return Position.WHITE;
        return Position.EMPTY;
    }

    private static int averageRgb(Pixels px, float fx, float fy) {
        int x = Math.round(fx), y = Math.round(fy);
        int r = 0, g = 0, b = 0, n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= px.width() || yy >= px.height()) continue;
                int v = px.rgb(xx, yy);
                r += (v >> 16) & 0xFF;
                g += (v >> 8) & 0xFF;
                b += v & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    static int sat(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int median(int[] a, int n) {
        if (n == 0) return 0;
        int[] copy = Arrays.copyOf(a, n);
        Arrays.sort(copy);
        return copy[n / 2];
    }

    /** 돌 개수가 오목 규칙상 말이 되는지 (흑이 백보다 0~1개 많음) */
    public static boolean plausible(int[] stones) {
        int b = 0, w = 0;
        for (int v : stones) {
            if (v == Position.BLACK) b++;
            else if (v == Position.WHITE) w++;
        }
        return b - w >= -1 && b - w <= 2;
    }
}
