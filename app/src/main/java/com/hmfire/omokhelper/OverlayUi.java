package com.hmfire.omokhelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.hmfire.omokhelper.core.Analysis;
import com.hmfire.omokhelper.core.BoardReader;
import com.hmfire.omokhelper.core.Coords;
import com.hmfire.omokhelper.core.Rule;

/**
 * 게임 위에 뜨는 창 세 가지:
 *  - 추천 고리 (화면 전체, 터치 통과)
 *  - 조작 패널 (끌어서 옮길 수 있음)
 *  - 판 맞추기 화면 (두 모서리를 끌어서 맞춤)
 */
final class OverlayUi {
    interface Host {
        void onCalibrationChanged();

        void onCalibrationStarted();

        void onPauseChanged(boolean paused);

        void onStopRequested();
    }

    static final int[] RANK_COLORS = {0xFFFF3B30, 0xFFFF9500, 0xFF2F7BFF};

    private final Context ctx;
    private final WindowManager wm;
    private final Settings settings;
    private final Host host;
    private final float density;
    private final int screenW, screenH;

    private RingView ringView;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private LinearLayout body;
    private TextView headlineView, detailView, warningView, colorChip, timeChip, foldChip, pauseButton;

    private FrameLayout calibRoot;
    private CalibrationView calibView;
    private LinearLayout calibBar;
    private TextView calibSizeButton;
    private boolean calibBarAtTop;

    private volatile boolean calibrating;
    private boolean paused, folded, shown;

    OverlayUi(Context ctx, Settings settings, Host host, int screenW, int screenH) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.settings = settings;
        this.host = host;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.screenW = screenW;
        this.screenH = screenH;
    }

    boolean isCalibrating() {
        return calibrating;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private WindowManager.LayoutParams params(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    void show() {
        if (shown) return;
        shown = true;
        ringView = new RingView(ctx);
        WindowManager.LayoutParams ringLp = params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // 안드로이드 12+ : 불투명도 0.8 이하인 창만 터치가 아래 게임으로 통과된다
        ringLp.alpha = 0.8f;
        wm.addView(ringView, ringLp);

        buildPanel();
        wm.addView(panel, panelLp);
        updateChips();
        setStatus("준비 완료", "게임 화면에서 [판 맞추기]를 눌러 주세요");
    }

    void hide() {
        if (!shown) return;
        shown = false;
        removeSafely(ringView);
        removeSafely(panel);
        removeSafely(calibRoot);
        calibRoot = null;
        calibrating = false;
    }

    private void removeSafely(View v) {
        if (v == null || !v.isAttachedToWindow()) return;
        try {
            wm.removeViewImmediate(v);
        } catch (RuntimeException ignored) {
            // 이미 떼어진 창
        }
    }

    // ------------------------------------------------------------------ 패널

    @SuppressLint("ClickableViewAccessibility")
    private void buildPanel() {
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(rounded(0xE61C1F26, dp(14)));
        panel.setMinimumWidth(dp(230));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("⠿ 오목 헬퍼", 14, Color.WHITE, true);
        title.setPadding(0, dp(4), dp(8), dp(4));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        colorChip = chip("나: 자동");
        colorChip.setOnClickListener(v -> {
            settings.cycleMyColor();
            updateChips();
        });
        timeChip = chip("⏱ 1.5초");
        timeChip.setOnClickListener(v -> {
            settings.cycleThinkMs();
            updateChips();
        });
        foldChip = chip("–");
        foldChip.setOnClickListener(v -> setFolded(!folded));
        header.addView(colorChip);
        header.addView(timeChip, marginStart(dp(6)));
        header.addView(foldChip, marginStart(dp(6)));
        panel.addView(header);

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        headlineView = text("", 14, Color.WHITE, true);
        headlineView.setPadding(0, dp(6), 0, dp(2));
        headlineView.setMaxWidth(dp(290));
        detailView = text("", 13, 0xFFD5D8E0, false);
        detailView.setMaxWidth(dp(290));
        detailView.setLineSpacing(dp(2), 1f);
        warningView = text("", 12, 0xFFFFC94D, true);
        warningView.setMaxWidth(dp(290));
        warningView.setVisibility(View.GONE);
        body.addView(headlineView);
        body.addView(detailView);
        body.addView(warningView);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        TextView calib = button("판 맞추기", 0xFF3D7BF7);
        calib.setOnClickListener(v -> startCalibration());
        pauseButton = button("일시정지", 0xFF4A4F5C);
        pauseButton.setOnClickListener(v -> {
            paused = !paused;
            pauseButton.setText(paused ? "계속" : "일시정지");
            if (paused) {
                clearRings();
                setStatus("일시정지됨", "[계속]을 누르면 다시 분석합니다");
            }
            host.onPauseChanged(paused);
        });
        TextView stop = button("종료", 0xFF8E3B46);
        stop.setOnClickListener(v -> host.onStopRequested());
        buttons.addView(calib, new LinearLayout.LayoutParams(0, dp(36), 1.2f));
        buttons.addView(pauseButton, weighted(1f, dp(6)));
        buttons.addView(stop, weighted(0.8f, dp(6)));
        body.addView(buttons);
        panel.addView(body);

        panelLp = params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        panelLp.x = Math.max(0, Math.min(settings.panelX(), screenW - dp(120)));
        panelLp.y = Math.max(0, Math.min(settings.panelY(), screenH - dp(80)));

        title.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = panelLp.x;
                        startY = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = Math.max(0, startX + Math.round(e.getRawX() - downX));
                        panelLp.y = Math.max(0, startY + Math.round(e.getRawY() - downY));
                        wm.updateViewLayout(panel, panelLp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        settings.setPanelPosition(panelLp.x, panelLp.y);
                        updateOverlapWarning();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setFolded(boolean f) {
        folded = f;
        body.setVisibility(f ? View.GONE : View.VISIBLE);
        foldChip.setText(f ? "+" : "–");
    }

    private void updateChips() {
        colorChip.setText("나: " + Settings.colorLabel(settings.myColor()));
        timeChip.setText("⏱ " + Settings.thinkLabel(settings.thinkMs()));
    }

    void setStatus(String headline, String detail) {
        if (panel == null) return;
        headlineView.setText(headline);
        detailView.setText(detail);
        detailView.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
        updateChips();
        updateOverlapWarning();
    }

    void showThinking(int toMove, int thinkMs, Rule rule) {
        if (calibrating || panel == null) return;
        clearRings();
        headlineView.setText(Analysis.colorName(toMove) + " 차례 · 생각 중… (" + Settings.thinkLabel(thinkMs) + ")");
        updateChips();
    }

    void showAnalysis(Analysis a, float[] cal, int size, Rule rule) {
        if (calibrating || panel == null) return;
        int[] cells = new int[a.moves.size()];
        for (int i = 0; i < cells.length; i++) cells[i] = a.moves.get(i).cell;
        ringView.set(cells, cal, size);

        headlineView.setText(a.headline.isEmpty() ? Analysis.colorName(a.toMove) + " 차례" : a.headline);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < a.moves.size(); i++) {
            Analysis.Suggestion s = a.moves.get(i);
            int start = sb.length();
            sb.append("● ");
            sb.setSpan(new ForegroundColorSpan(RANK_COLORS[Math.min(i, 2)]), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int nameStart = sb.length();
            sb.append(Coords.name(s.cell, size));
            sb.setSpan(new StyleSpan(Typeface.BOLD), nameStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ").append(s.reason).append('\n');
        }
        if (a.line.length > 1) sb.append("수순: ").append(Coords.line(a.line, size, 7)).append('\n');
        if (a.winner == 0) {
            sb.append(rule.label).append(" · ").append(String.valueOf(size)).append("줄");
            if (a.depth > 0) sb.append(" · 깊이 ").append(String.valueOf(a.depth));
            sb.append(" · ").append(String.format(java.util.Locale.US, "%.1f초", a.elapsedMs / 1000f));
        }
        detailView.setText(sb);
        detailView.setVisibility(View.VISIBLE);
        updateChips();
        updateOverlapWarning();
    }

    void clearRings() {
        if (ringView != null) ringView.set(new int[0], null, 15);
    }

    /** 패널이 바둑판을 가리면 그 부분의 돌을 못 읽으므로 알려 준다 */
    private void updateOverlapWarning() {
        if (panel == null) return;
        float[] cal = settings.calibration(screenW, screenH);
        boolean overlap = false;
        if (cal != null && panel.getWidth() > 0) {
            float pad = Math.abs(cal[2] - cal[0]) / 28f;
            float l = Math.min(cal[0], cal[2]) - pad, r = Math.max(cal[0], cal[2]) + pad;
            float t = Math.min(cal[1], cal[3]) - pad, b = Math.max(cal[1], cal[3]) + pad;
            int[] loc = new int[2];
            panel.getLocationOnScreen(loc);
            overlap = loc[0] < r && loc[0] + panel.getWidth() > l && loc[1] < b && loc[1] + panel.getHeight() > t;
        }
        warningView.setText("⚠ 패널이 바둑판을 가리고 있어요. 제목(⠿)을 끌어 판 밖으로 옮겨 주세요");
        warningView.setVisibility(overlap ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ 판 맞추기

    private void startCalibration() {
        if (calibrating) return;
        calibrating = true;
        host.onCalibrationStarted();
        clearRings();
        panel.setVisibility(View.GONE);

        float[] start = settings.lastCalibration();
        if (start == null || start[2] > screenW || start[3] > screenH) {
            float side = screenW * 0.88f;
            float top = Math.max(screenH * 0.25f, (screenH - side) / 2f);
            start = new float[]{screenW * 0.06f, top, screenW * 0.94f, top + side};
        }
        calibRoot = new FrameLayout(ctx);
        calibView = new CalibrationView(ctx, start, settings.boardSize());
        calibRoot.addView(calibView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        calibBar = buildCalibrationBar();
        calibBarAtTop = start[3] > screenH * 0.7f;
        calibRoot.addView(calibBar, calibBarParams());

        WindowManager.LayoutParams lp = params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        wm.addView(calibRoot, lp);
    }

    private FrameLayout.LayoutParams calibBarParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, calibBarAtTop ? Gravity.TOP : Gravity.BOTTOM);
        int m = dp(10);
        lp.setMargins(m, calibBarAtTop ? dp(36) : m, m, calibBarAtTop ? m : dp(48));
        return lp;
    }

    private LinearLayout buildCalibrationBar() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(12));
        bar.setBackground(rounded(0xEE1C1F26, dp(14)));

        TextView help = text("분홍 십자를 끌어서 바둑판의 맨 왼쪽 위 교차점과 맨 오른쪽 아래 교차점에 맞추세요. "
                + "파란 격자가 판의 선과 겹치면 됩니다.", 13, 0xFFE8EAF0, false);
        bar.addView(help);

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);
        calibSizeButton = button(settings.boardSize() + "줄", 0xFF4A4F5C);
        calibSizeButton.setOnClickListener(v -> {
            int next = calibView.size == 15 ? 19 : 15;
            calibView.setSize(next);
            calibSizeButton.setText(next + "줄");
        });
        row1.addView(calibSizeButton, new LinearLayout.LayoutParams(0, dp(40), 1.3f));
        String[] arrows = {"◀", "▲", "▼", "▶"};
        int[][] deltas = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};
        for (int i = 0; i < 4; i++) {
            TextView b = button(arrows[i], 0xFF4A4F5C);
            final int[] d = deltas[i];
            b.setOnClickListener(v -> calibView.nudge(d[0], d[1]));
            row1.addView(b, weighted(1f, dp(6)));
        }
        TextView move = button("⇅", 0xFF4A4F5C);
        move.setOnClickListener(v -> {
            calibBarAtTop = !calibBarAtTop;
            calibBar.setLayoutParams(calibBarParams());
        });
        row1.addView(move, weighted(0.9f, dp(6)));
        bar.addView(row1);

        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        TextView cancel = button("취소", 0xFF4A4F5C);
        cancel.setOnClickListener(v -> endCalibration(false));
        TextView save = button("저장", 0xFF3D7BF7);
        save.setOnClickListener(v -> endCalibration(true));
        row2.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1f));
        row2.addView(save, weighted(2f, dp(8)));
        bar.addView(row2);
        return bar;
    }

    private void endCalibration(boolean save) {
        if (save) {
            float[] p = calibView.points;
            if (p[2] - p[0] < dp(60) || p[3] - p[1] < dp(60)) {
                Toast.makeText(ctx, "왼쪽 위 / 오른쪽 아래 모서리를 다시 확인해 주세요", Toast.LENGTH_SHORT).show();
                return;
            }
            settings.setBoardSize(calibView.size);
            settings.setCalibration(p[0], p[1], p[2], p[3], screenW, screenH);
        }
        removeSafely(calibRoot);
        calibRoot = null;
        calibView = null;
        calibrating = false;
        panel.setVisibility(View.VISIBLE);
        if (save) {
            setStatus("판 위치 저장됨", "판을 읽는 중… (" + settings.boardSize() + "줄)");
        }
        host.onCalibrationChanged();
    }

    // ------------------------------------------------------------------ 작은 도우미

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView chip(String s) {
        TextView t = text(s, 12, Color.WHITE, true);
        t.setPadding(dp(9), dp(5), dp(9), dp(5));
        t.setBackground(rounded(0xFF3A3F4B, dp(12)));
        t.setClickable(true);
        return t;
    }

    private TextView button(String s, int color) {
        TextView t = text(s, 14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(color, dp(9)));
        t.setClickable(true);
        return t;
    }

    private LinearLayout.LayoutParams weighted(float weight, int marginStart) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMarginStart(marginStart);
        return lp;
    }

    private LinearLayout.LayoutParams marginStart(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(m);
        return lp;
    }

    // ------------------------------------------------------------------ 추천 고리

    /** 추천 자리에 고리를 그린다. 가운데는 돌 인식에 쓰이므로 비워 둔다 */
    static final class RingView extends View {
        private int[] cells = new int[0];
        private float[] cal;
        private int size = 15;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];

        RingView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
        }

        void set(int[] cells, float[] cal, int size) {
            this.cells = cells;
            this.cal = cal;
            this.size = size;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (cal == null || cells.length == 0) return;
            getLocationOnScreen(loc);
            float sx = (cal[2] - cal[0]) / (size - 1), sy = (cal[3] - cal[1]) / (size - 1);
            float spacing = Math.min(Math.abs(sx), Math.abs(sy));
            float stroke = Math.max(2f, spacing * 0.08f);
            // 안쪽 가장자리가 인식 영역(SAMPLE_RADIUS)보다 바깥에 오도록
            float radius = spacing * (BoardReader.SAMPLE_RADIUS + 0.06f) + stroke / 2f;
            paint.setStrokeWidth(stroke);
            for (int i = cells.length - 1; i >= 0; i--) {
                int r = cells[i] / size, c = cells[i] % size;
                float x = cal[0] + c * sx - loc[0], y = cal[1] + r * sy - loc[1];
                paint.setColor(RANK_COLORS[Math.min(i, 2)]);
                canvas.drawCircle(x, y, radius, paint);
            }
        }
    }

    /** 판 맞추기: 두 모서리 십자를 끌어서 맞추고, 격자 미리보기로 확인 */
    final class CalibrationView extends View {
        final float[] points;
        int size;
        private int active = -1, selected = 0;
        private float grabDx, grabDy;
        private final int[] loc = new int[2];
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);

        CalibrationView(Context c, float[] start, int size) {
            super(c);
            this.points = start.clone();
            this.size = size;
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(Math.max(1f, dp(1.2f)));
            grid.setColor(0xCC00D8FF);
            handle.setStyle(Paint.Style.STROKE);
            label.setColor(Color.WHITE);
            label.setTextSize(dp(12));
            label.setFakeBoldText(true);
            labelBg.setColor(0xCC000000);
        }

        void setSize(int s) {
            size = s;
            invalidate();
        }

        void nudge(int dx, int dy) {
            points[selected * 2] += dx;
            points[selected * 2 + 1] += dy;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(loc);
            canvas.drawColor(0x22000000);
            float ox = -loc[0], oy = -loc[1];
            float x0 = points[0] + ox, y0 = points[1] + oy, x1 = points[2] + ox, y1 = points[3] + oy;
            for (int i = 0; i < size; i++) {
                float t = i / (float) (size - 1);
                float y = y0 + (y1 - y0) * t, x = x0 + (x1 - x0) * t;
                canvas.drawLine(x0, y, x1, y, grid);
                canvas.drawLine(x, y0, x, y1, grid);
            }
            drawHandle(canvas, x0, y0, "왼쪽 위", selected == 0, true);
            drawHandle(canvas, x1, y1, "오른쪽 아래", selected == 1, false);
        }

        private void drawHandle(Canvas canvas, float x, float y, String text, boolean sel, boolean labelAbove) {
            handle.setColor(0xFFFF2D95);
            handle.setStrokeWidth(sel ? dp(2.2f) : dp(1.4f));
            float arm = dp(30);
            canvas.drawLine(x - arm, y, x - dp(4), y, handle);
            canvas.drawLine(x + dp(4), y, x + arm, y, handle);
            canvas.drawLine(x, y - arm, x, y - dp(4), handle);
            canvas.drawLine(x, y + dp(4), x, y + arm, handle);
            canvas.drawCircle(x, y, dp(sel ? 22 : 18), handle);
            float tw = label.measureText(text);
            float tx = labelAbove ? x + dp(8) : x - tw - dp(8);
            float ty = labelAbove ? y - dp(30) : y + dp(40);
            canvas.drawRoundRect(tx - dp(5), ty - dp(14), tx + tw + dp(5), ty + dp(5), dp(5), dp(5), labelBg);
            canvas.drawText(text, tx, ty, label);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float rx = e.getRawX(), ry = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float d0 = dist(rx, ry, points[0], points[1]);
                    float d1 = dist(rx, ry, points[2], points[3]);
                    active = d0 <= d1 ? 0 : 1;
                    if (Math.min(d0, d1) > dp(90)) {
                        // 멀리 눌렀으면 그 자리로 바로 옮긴다
                        points[active * 2] = rx;
                        points[active * 2 + 1] = ry;
                    }
                    selected = active;
                    grabDx = points[active * 2] - rx;
                    grabDy = points[active * 2 + 1] - ry;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (active >= 0) {
                        points[active * 2] = rx + grabDx;
                        points[active * 2 + 1] = ry + grabDy;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    active = -1;
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private float dist(float ax, float ay, float bx, float by) {
            return (float) Math.hypot(ax - bx, ay - by);
        }
    }
}
