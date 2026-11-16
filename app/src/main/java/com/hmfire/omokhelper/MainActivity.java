package com.hmfire.omokhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hmfire.omokhelper.core.Rule;

/** 첫 화면: 설정, 권한, 시작/중지 */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    private static final int ACCENT = 0xFF3D7BF7;
    private static final int TEXT = 0xFF1C1F26;
    private static final int SUBTLE = 0xFF6B7280;

    private Settings settings;
    private float density;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView permissionText, permissionButton, startButton;
    private boolean askedNotifications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        density = getResources().getDisplayMetrics().density;
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ 화면 구성

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(0xFFF3F4F7);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(col);

        TextView title = text("⚫⚪ 나만의 오목 헬퍼", 25, TEXT, true);
        col.addView(title);
        TextView sub = text("화면에 보이는 오목판을 읽고, 다음에 둘 좋은 자리를 게임 위에 표시해 줍니다.", 14, SUBTLE, false);
        sub.setPadding(0, dp(6), 0, dp(14));
        col.addView(sub);

        // 게임 설정
        LinearLayout game = card(col, "게임 설정");
        label(game, "판 크기");
        game.addView(segmented(new String[]{"15줄", "19줄"}, settings.boardSize() == 19 ? 1 : 0,
                i -> settings.setBoardSize(Settings.SIZES[i])));
        label(game, "규칙");
        game.addView(segmented(new String[]{"자유룰", "렌주룰"}, settings.rule() == Rule.RENJU ? 1 : 0,
                i -> settings.setRule(i == 1 ? Rule.RENJU : Rule.FREESTYLE)));
        hint(game, "렌주룰: 흑은 3-3, 4-4, 6목 이상 금지 (추천에서 제외)");

        label(game, "AI 생각 시간");
        String[] times = new String[Settings.THINK_MS.length];
        int timeIndex = 0;
        for (int i = 0; i < times.length; i++) {
            times[i] = Settings.thinkLabel(Settings.THINK_MS[i]);
            if (Settings.THINK_MS[i] == settings.thinkMs()) timeIndex = i;
        }
        game.addView(segmented(times, timeIndex, i -> settings.setThinkMs(Settings.THINK_MS[i])));
        hint(game, "길게 줄수록 더 많은 수를 앞서 읽어서 강해집니다. 게임 중 패널의 ⏱ 칩으로도 바꿀 수 있어요.");

        label(game, "추천 개수");
        int topIndex = settings.topN() == 1 ? 0 : settings.topN() == 5 ? 2 : 1;
        game.addView(segmented(new String[]{"1개", "3개", "5개"}, topIndex, i -> settings.setTopN(Settings.TOP_N[i])));

        label(game, "내 돌");
        game.addView(segmented(new String[]{"자동", "흑", "백"}, settings.myColor(),
                i -> settings.setMyColor(Settings.MY_COLORS[i])));
        hint(game, "자동: 지금 차례인 쪽을 추천 · 흑/백: 내 차례일 때만 추천");

        // 시작
        LinearLayout run = card(col, "시작하기");
        permissionText = text("", 14, TEXT, false);
        run.addView(permissionText);
        permissionButton = button("다른 앱 위에 표시 권한 허용", 0xFF4A4F5C);
        permissionButton.setOnClickListener(v -> requestOverlayPermission());
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        pbl.topMargin = dp(10);
        run.addView(permissionButton, pbl);
        startButton = button("화면 감지 시작", ACCENT);
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        startButton.setOnClickListener(v -> onStartClicked());
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        sbl.topMargin = dp(10);
        run.addView(startButton, sbl);

        // 사용 방법
        LinearLayout how = card(col, "사용 방법");
        how.addView(text(
                "1. 위에서 판 크기와 규칙을 게임과 같게 고르고, AI 생각 시간을 정합니다.\n"
                        + "2. [화면 감지 시작] → 화면 녹화를 허용합니다. (전체 화면 공유)\n"
                        + "3. 오목 게임을 열고 떠 있는 패널의 [판 맞추기]를 누릅니다.\n"
                        + "4. 분홍 십자 두 개를 바둑판의 맨 왼쪽 위 / 맨 오른쪽 아래 교차점에 맞추고 [저장].\n"
                        + "5. 판이 바뀔 때마다 추천 자리에 고리가 생깁니다.\n"
                        + "   빨강 = 1순위 · 주황 = 2순위 · 파랑 = 3순위 이하\n"
                        + "6. 패널은 제목(⠿)을 끌어서 바둑판 밖으로 옮겨 두세요. 패널이 판을 가리면 돌을 못 읽습니다.",
                14, TEXT, false));

        // AI가 쓰는 기술
        LinearLayout tech = card(col, "AI가 쓰는 오목 기술");
        tech.addView(text(
                "• 5목 완성 · 4 막기 · 열린4 · 4-4 · 4-3 · 3-3\n"
                        + "• VCF: 4를 연달아 두어 이기는 수순\n"
                        + "• VCT: 열린3과 4를 섞어 이기는 수순 (상대의 모든 방어를 확인)\n"
                        + "• 상대 VCF/VCT 차단: 상대 필승 수순을 끊는 자리만 추천\n"
                        + "• 4로 반격: 막기 대신 4를 두어 선수를 뺏기\n"
                        + "• 렌주룰 금수(3-3, 4-4, 장목) 판정과 금수 유도(흑이 막을 자리를 금수로 만들기)\n"
                        + "• 렌주 26주형 이름 알아보기 (화월, 포월, 운월 …)\n"
                        + "• 생각 시간 동안 여러 수 앞을 읽는 알파-베타 탐색",
                14, TEXT, false));

        TextView warn = text("연습, AI 대전, 공부용으로 만들었습니다. 사람과 하는 온라인 대국에서 사용하면 상대에게 불공정하고 "
                + "게임 이용약관 위반으로 제재될 수 있어요.", 12, SUBTLE, false);
        warn.setPadding(dp(4), dp(14), dp(4), 0);
        col.addView(warn);
        return scroll;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(rounded(Color.WHITE, dp(16), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        c.addView(text(title, 17, TEXT, true));
        return c;
    }

    private void label(LinearLayout parent, String s) {
        TextView t = text(s, 14, TEXT, true);
        t.setPadding(0, dp(14), 0, dp(6));
        parent.addView(t);
    }

    private void hint(LinearLayout parent, String s) {
        TextView t = text(s, 12, SUBTLE, false);
        t.setPadding(0, dp(5), 0, 0);
        parent.addView(t);
    }

    interface OnPick {
        void pick(int index);
    }

    /** 가로로 나란한 선택 버튼 (하나만 선택) */
    private LinearLayout segmented(String[] labels, int selected, OnPick onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = text(labels[i], 14, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setClickable(true);
            items[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (i > 0) lp.setMarginStart(dp(6));
            row.addView(t, lp);
            final int index = i;
            t.setOnClickListener(v -> {
                styleSegments(items, index);
                onPick.pick(index);
            });
        }
        styleSegments(items, selected);
        return row;
    }

    private void styleSegments(TextView[] items, int selected) {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == selected;
            items[i].setBackground(rounded(on ? ACCENT : 0xFFF3F4F7, dp(10), on ? 0 : 0xFFD9DCE3));
            items[i].setTextColor(on ? Color.WHITE : TEXT);
        }
    }

    private GradientDrawable rounded(int color, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, int color) {
        TextView t = text(s, 15, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(color, dp(12), 0));
        t.setClickable(true);
        return t;
    }

    // ------------------------------------------------------------------ 동작

    private boolean canDrawOverlays() {
        return android.provider.Settings.canDrawOverlays(this);
    }

    private void refresh() {
        boolean overlay = canDrawOverlays();
        permissionText.setText(overlay ? "✅ 다른 앱 위에 표시 권한: 허용됨" : "⚠ 다른 앱 위에 표시 권한이 필요합니다");
        permissionButton.setVisibility(overlay ? View.GONE : View.VISIBLE);
        boolean running = CaptureService.running;
        startButton.setText(running ? "감지 중지" : "화면 감지 시작");
        startButton.setBackground(rounded(running ? 0xFF8E3B46 : ACCENT, dp(12), 0));
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void onStartClicked() {
        if (CaptureService.running) {
            stopService(new Intent(this, CaptureService.class));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        if (!canDrawOverlays()) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해 주세요", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        requestCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // 앱 하나만 공유하면 좌표가 어긋나므로 전체 화면으로 고정
            intent = mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            intent = mpm.createScreenCaptureIntent();
        }
        startActivityForResult(intent, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 녹화를 허용해야 판을 읽을 수 있어요", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        Toast.makeText(this, "오목 게임을 열고 패널의 [판 맞추기]를 눌러 주세요", Toast.LENGTH_LONG).show();
        handler.postDelayed(this::refresh, 400);
        moveTaskToBack(true);
    }
}
