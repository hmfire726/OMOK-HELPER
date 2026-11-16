package com.hmfire.omokhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.hmfire.omokhelper.core.Analysis;
import com.hmfire.omokhelper.core.BoardReader;
import com.hmfire.omokhelper.core.Engine;
import com.hmfire.omokhelper.core.Openings;
import com.hmfire.omokhelper.core.Position;
import com.hmfire.omokhelper.core.Rule;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 화면 캡처 → 돌 읽기 → 분석 → 오버레이 표시를 반복하는 포그라운드 서비스.
 */
public class CaptureService extends Service implements OverlayUi.Host {
    private static final String TAG = "OmokHelper";
    static final String EXTRA_CODE = "result_code";
    static final String EXTRA_DATA = "result_data";
    static final String ACTION_STOP = "com.hmfire.omokhelper.STOP";
    private static final String CHANNEL = "capture";
    private static final int NOTIFICATION_ID = 7;
    private static final long TICK_MS = 500;

    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;
    private volatile Handler capture;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "omok-analysis");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private volatile OverlayUi overlay;
    private Settings settings;

    private int screenW, screenH;
    private int imageW, imageH;

    // 캡처 스레드에서만 사용
    private int[] pendingRead;
    private int[] stableBoard;
    private int stableSize;
    private String analyzedKey;
    private String lastStatus;
    /** 렌주 26주형 이름 (돌 3개일 때 알아낸 뒤 게임이 이어지는 동안 유지) */
    private String openingName;

    private volatile Engine currentEngine;
    private volatile int generation;
    private volatile boolean paused;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (!Settings.KEY_SIZE.equals(key) && !Settings.KEY_RULE.equals(key) && !Settings.KEY_THINK.equals(key)
                && !Settings.KEY_TOP_N.equals(key) && !Settings.KEY_MY_COLOR.equals(key)) return;
        Handler c = capture;
        if (c != null) c.post(this::reanalyze);
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            main.post(CaptureService.this::stopSelf);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (projection != null) return START_NOT_STICKY;

        Intent data = intent == null ? null : getResultData(intent);
        int code = intent == null ? 0 : intent.getIntExtra(EXTRA_CODE, 0);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            Log.e(TAG, "getMediaProjection", e);
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        settings = new Settings(this);
        settings.prefs().registerOnSharedPreferenceChangeListener(prefListener);

        captureThread = new HandlerThread("omok-capture");
        captureThread.start();
        capture = new Handler(captureThread.getLooper());

        projection.registerCallback(projectionCallback, main);
        startCapture();

        overlay = new OverlayUi(uiContext(), settings, this, screenW, screenH);
        overlay.show();
        capture.postDelayed(tick, 300);
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private static Intent getResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    private Context uiContext() {
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            return createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        return this;
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "화면 감지", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("오목 헬퍼가 화면을 보고 있는 동안 표시됩니다");
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("오목 헬퍼 실행 중")
                .setContentText("바둑판을 보고 추천 수를 표시합니다")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "중지", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return dm;
    }

    private void startCapture() {
        DisplayMetrics dm = realMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        // 너무 큰 이미지는 느리므로 짧은 변이 720px 정도가 되게 줄인다
        float scale = Math.min(1f, 720f / Math.min(screenW, screenH));
        imageW = Math.max(2, Math.round(screenW * scale));
        imageH = Math.max(2, Math.round(screenH * scale));
        reader = ImageReader.newInstance(imageW, imageH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("omok-helper", imageW, imageH,
                Math.max(1, Math.round(dm.densityDpi * scale)),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capture);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                captureOnce();
            } catch (RuntimeException e) {
                Log.w(TAG, "capture", e);
            }
            Handler c = capture;
            if (c != null) c.postDelayed(this, TICK_MS);
        }
    };

    /** 캡처 스레드에서 실행 */
    private void captureOnce() {
        OverlayUi ov = overlay;
        Image image = reader == null ? null : reader.acquireLatestImage();
        try {
            if (paused || ov == null || ov.isCalibrating()) return;
            DisplayMetrics dm = realMetrics();
            if (dm.widthPixels != screenW || dm.heightPixels != screenH) {
                status("화면 방향이 바뀌었어요", "처음 시작할 때와 같은 방향(세로)으로 돌려 주세요");
                analyzedKey = null;
                return;
            }
            float[] cal = settings.calibration(screenW, screenH);
            if (cal == null) {
                status("판 위치를 맞춰 주세요", "게임 화면에서 [판 맞추기]를 눌러 바둑판의 두 모서리를 맞추고 저장하세요");
                analyzedKey = null;
                return;
            }
            int size = settings.boardSize();
            if (image == null) {
                // 화면이 그대로면 새 이미지가 오지 않는다 → 마지막으로 읽은 판을 확정
                if (pendingRead != null && pendingRead.length == size * size) onStableBoard(pendingRead, size);
                return;
            }
            float sx = imageW / (float) screenW, sy = imageH / (float) screenH;
            int[] stones = BoardReader.read(new ImagePixels(image),
                    cal[0] * sx, cal[1] * sy, cal[2] * sx, cal[3] * sy, size);
            if (pendingRead == null || !Arrays.equals(stones, pendingRead)) {
                // 돌이 놓이는 애니메이션 중일 수 있으니 한 번 더 같은 판이 읽힐 때까지 기다린다
                pendingRead = stones;
                return;
            }
            onStableBoard(stones, size);
        } finally {
            if (image != null) image.close();
        }
    }

    private void reanalyze() {
        analyzedKey = null;
        lastStatus = null;
        if (stableBoard != null && settings != null && stableSize == settings.boardSize()) {
            onStableBoard(stableBoard, stableSize);
        }
    }

    private void onStableBoard(int[] stones, int size) {
        stableBoard = stones;
        stableSize = size;
        String key = Arrays.hashCode(stones) + "|" + settings.signature();
        if (key.equals(analyzedKey)) return;
        analyzedKey = key;

        if (!BoardReader.plausible(stones)) {
            cancelAnalysis();
            status("돌 개수가 맞지 않아요", "흑/백 돌 수 차이가 이상합니다. [판 맞추기]로 위치를 다시 맞추거나 판 크기(15/19)를 확인하세요");
            return;
        }
        int count = 0;
        for (int v : stones) if (v != Position.EMPTY) count++;
        if (count < 3) openingName = null;
        else if (count == 3) openingName = Openings.name(stones, size);
        int toMove = Position.guessToMove(stones);
        int my = settings.myColor();
        if (my != 0 && my != toMove) {
            cancelAnalysis();
            status("상대 차례 — 기다리는 중", "나: " + Settings.colorLabel(my) + " · 지금은 " + Analysis.colorName(toMove) + " 차례");
            return;
        }
        startAnalysis(stones.clone(), size, toMove);
    }

    private void cancelAnalysis() {
        generation++;
        Engine e = currentEngine;
        if (e != null) e.cancel();
        main.post(() -> {
            OverlayUi ov = overlay;
            if (ov != null) ov.clearRings();
        });
    }

    private void startAnalysis(int[] stones, int size, int toMove) {
        cancelAnalysis();
        final int gen = generation;
        final Rule rule = settings.rule();
        final int thinkMs = settings.thinkMs();
        final int topN = settings.topN();
        final float[] cal = settings.calibration(screenW, screenH);
        final String opening = openingName;
        lastStatus = null;
        final Engine engine = new Engine(new Position(size, rule, stones, toMove));
        currentEngine = engine;
        main.post(() -> {
            OverlayUi ov = overlay;
            if (ov != null && gen == generation) ov.showThinking(toMove, thinkMs, rule);
        });
        analysisExecutor.execute(() -> {
            if (gen != generation) return;
            Analysis a;
            try {
                a = engine.analyze(thinkMs, topN);
            } catch (RuntimeException e) {
                Log.e(TAG, "analyze", e);
                return;
            }
            if (engine.isCancelled() || gen != generation) return;
            main.post(() -> {
                OverlayUi ov = overlay;
                if (ov != null && gen == generation && cal != null) ov.showAnalysis(a, cal, size, rule, opening);
            });
        });
    }

    private void status(String headline, String detail) {
        String key = headline + detail;
        if (key.equals(lastStatus)) return;
        lastStatus = key;
        main.post(() -> {
            OverlayUi ov = overlay;
            if (ov != null) {
                ov.clearRings();
                ov.setStatus(headline, detail);
            }
        });
    }

    // ---------------------------------------------------------- OverlayUi.Host

    @Override
    public void onCalibrationChanged() {
        Handler c = capture;
        if (c != null) c.post(() -> {
            pendingRead = null;
            stableBoard = null;
            reanalyze();
        });
    }

    @Override
    public void onCalibrationStarted() {
        cancelAnalysis();
    }

    @Override
    public void onPauseChanged(boolean p) {
        paused = p;
        if (p) {
            cancelAnalysis();
        } else {
            Handler c = capture;
            if (c != null) c.post(this::reanalyze);
        }
    }

    @Override
    public void onStopRequested() {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        generation++;
        Engine e = currentEngine;
        if (e != null) e.cancel();
        analysisExecutor.shutdownNow();
        if (settings != null) settings.prefs().unregisterOnSharedPreferenceChangeListener(prefListener);
        if (overlay != null) overlay.hide();
        overlay = null;
        Handler c = capture;
        capture = null;
        final VirtualDisplay vd = virtualDisplay;
        final ImageReader ir = reader;
        final MediaProjection mp = projection;
        final HandlerThread thread = captureThread;
        Runnable release = () -> {
            if (vd != null) vd.release();
            if (ir != null) ir.close();
            if (mp != null) {
                mp.unregisterCallback(projectionCallback);
                mp.stop();
            }
            if (thread != null) thread.quitSafely();
        };
        if (c != null) {
            // 캡처 스레드가 이미지를 읽는 중일 수 있으니 그 스레드에서 정리한다
            c.removeCallbacksAndMessages(null);
            c.post(release);
        } else {
            release.run();
        }
        super.onDestroy();
    }
}
