package com.hmfire.omokhelper;

import android.content.Context;
import android.content.SharedPreferences;

import com.hmfire.omokhelper.core.Engine;
import com.hmfire.omokhelper.core.Position;
import com.hmfire.omokhelper.core.Rule;

/** 앱 설정 (SharedPreferences) */
final class Settings {
    static final String FILE = "omok_helper";

    static final String KEY_SIZE = "board_size";
    static final String KEY_RULE = "rule";
    static final String KEY_THINK = "think_ms";
    static final String KEY_TOP_N = "top_n";
    static final String KEY_MY_COLOR = "my_color";
    static final String KEY_CAL = "calibration";

    static final int[] SIZES = {15, 19};
    static final int[] THINK_MS = Engine.THINK_TIMES_MS;
    static final int[] TOP_N = {1, 3, 5};
    /** 0 = 자동(지금 차례), 1 = 흑, 2 = 백 */
    static final int[] MY_COLORS = {0, Position.BLACK, Position.WHITE};

    private final SharedPreferences sp;

    Settings(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    SharedPreferences prefs() {
        return sp;
    }

    int boardSize() {
        int v = sp.getInt(KEY_SIZE, 15);
        return v == 19 ? 19 : 15;
    }

    void setBoardSize(int size) {
        sp.edit().putInt(KEY_SIZE, size).apply();
    }

    Rule rule() {
        return sp.getInt(KEY_RULE, 0) == 1 ? Rule.RENJU : Rule.FREESTYLE;
    }

    void setRule(Rule rule) {
        sp.edit().putInt(KEY_RULE, rule == Rule.RENJU ? 1 : 0).apply();
    }

    int thinkMs() {
        int v = sp.getInt(KEY_THINK, 1500);
        for (int t : THINK_MS) if (t == v) return v;
        return 1500;
    }

    void setThinkMs(int ms) {
        sp.edit().putInt(KEY_THINK, ms).apply();
    }

    /** 0.5 → 1.5 → 3 → 4 → 5 → 0.5 … */
    int cycleThinkMs() {
        int cur = thinkMs();
        int next = THINK_MS[0];
        for (int i = 0; i < THINK_MS.length; i++) {
            if (THINK_MS[i] == cur) next = THINK_MS[(i + 1) % THINK_MS.length];
        }
        setThinkMs(next);
        return next;
    }

    int topN() {
        int v = sp.getInt(KEY_TOP_N, 3);
        return v == 1 || v == 5 ? v : 3;
    }

    void setTopN(int n) {
        sp.edit().putInt(KEY_TOP_N, n).apply();
    }

    int myColor() {
        int v = sp.getInt(KEY_MY_COLOR, 0);
        return v == Position.BLACK || v == Position.WHITE ? v : 0;
    }

    void setMyColor(int color) {
        sp.edit().putInt(KEY_MY_COLOR, color).apply();
    }

    int cycleMyColor() {
        int next = (myColor() + 1) % 3;
        setMyColor(next);
        return next;
    }

    /**
     * 판 위치 (화면 픽셀): {왼쪽위 x, y, 오른쪽아래 x, y}. 맞춘 적이 없거나
     * 화면 크기(방향)가 달라졌으면 null
     */
    float[] calibration(int screenW, int screenH) {
        String s = sp.getString(KEY_CAL, null);
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            if (p.length != 6) return null;
            if (Integer.parseInt(p[4]) != screenW || Integer.parseInt(p[5]) != screenH) return null;
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1]),
                    Float.parseFloat(p[2]), Float.parseFloat(p[3])};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 화면 크기와 상관없이 마지막으로 저장한 위치 (판 맞추기 시작 위치로 사용) */
    float[] lastCalibration() {
        String s = sp.getString(KEY_CAL, null);
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1]),
                    Float.parseFloat(p[2]), Float.parseFloat(p[3])};
        } catch (RuntimeException e) {
            return null;
        }
    }

    void setCalibration(float x0, float y0, float x1, float y1, int screenW, int screenH) {
        sp.edit().putString(KEY_CAL, x0 + "," + y0 + "," + x1 + "," + y1 + "," + screenW + "," + screenH).apply();
    }

    int panelX() {
        return sp.getInt("panel_x", 24);
    }

    int panelY() {
        return sp.getInt("panel_y", 140);
    }

    void setPanelPosition(int x, int y) {
        sp.edit().putInt("panel_x", x).putInt("panel_y", y).apply();
    }

    /** 분석 결과에 영향을 주는 설정을 한 줄로 */
    String signature() {
        return boardSize() + "/" + rule() + "/" + thinkMs() + "/" + topN() + "/" + myColor();
    }

    static String thinkLabel(int ms) {
        if (ms % 1000 == 0) return (ms / 1000) + "초";
        return (ms / 1000) + "." + (ms % 1000) / 100 + "초";
    }

    static String colorLabel(int color) {
        return color == Position.BLACK ? "흑" : color == Position.WHITE ? "백" : "자동";
    }
}
