package com.vluck.autoplayer;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.util.DisplayMetrics;

import java.util.ArrayList;

public class CaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static volatile boolean running = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private HandlerThread thread;
    private Handler handler;

    private long lastFrame = 0;
    private long lastOutcomeAction = 0;
    private long lastSteerAction = 0;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        running = true;

        createNotificationChannel();
        startForeground(7, buildNotification());

        thread = new HandlerThread("vluck-screen-analysis");
        thread.start();

        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Intent data = intent.getParcelableExtra(EXTRA_DATA);

        int resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
        );

        if (data != null && projection == null) {

            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(
                            MEDIA_PROJECTION_SERVICE
                    );

            projection = mgr.getMediaProjection(resultCode, data);

            DisplayMetrics dm =
                    getResources().getDisplayMetrics();

            int width = dm.widthPixels;
            int height = dm.heightPixels;
            int density = dm.densityDpi;

            imageReader = ImageReader.newInstance(
                    width,
                    height,
                    android.graphics.PixelFormat.RGBA_8888,
                    2
            );

            imageReader.setOnImageAvailableListener(
                    reader -> analyze(reader),
                    handler
            );

            virtualDisplay = projection.createVirtualDisplay(
                    "VluckAutoPlayer",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );
        }

        return START_STICKY;
    }

    private void analyze(ImageReader reader) {

        long now = System.currentTimeMillis();

        if (now - lastFrame < 85) {
            return;
        }

        lastFrame = now;

        Image image = null;

        try {

            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            Image.Plane plane = image.getPlanes()[0];

            int width = image.getWidth();
            int height = image.getHeight();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            int rowPadding =
                    rowStride - pixelStride * width;

            Bitmap full = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );

            full.copyPixelsFromBuffer(plane.getBuffer());

            if (handleOutcome(full)) {
                full.recycle();
                return;
            }

            steerToSafeGap(full);

            full.recycle();

        } catch (Throwable ignored) {

        } finally {

            if (image != null) {
                image.close();
            }
        }
    }

    private boolean handleOutcome(Bitmap bitmap) {

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int x1 = (int) (w * 0.25f);
        int x2 = (int) (w * 0.75f);
        int y1 = (int) (h * 0.65f);
        int y2 = (int) (h * 0.90f);

        int regionWidth = x2 - x1;
        int regionHeight = y2 - y1;
        int total = regionWidth * regionHeight;

        boolean[][] victoryMask =
                new boolean[regionHeight][regionWidth];

        boolean[][] defeatMask =
                new boolean[regionHeight][regionWidth];

        for (int y = 0; y < regionHeight; y++) {
            for (int x = 0; x < regionWidth; x++) {

                int c = bitmap.getPixel(x + x1, y + y1);

                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);

                victoryMask[y][x] =
                        b > 180 &&
                        g > 100 &&
                        r < 190 &&
                        b > r + 35;

                defeatMask[y][x] =
                        r > 115 &&
                        b > 175 &&
                        g < 195 &&
                        b > g + 25;
            }
        }

        int victoryArea =
                largestComponent(
                        victoryMask,
                        regionWidth,
                        regionHeight
                );

        int defeatArea =
                largestComponent(
                        defeatMask,
                        regionWidth,
                        regionHeight
                );

        int minimumArea =
                (int) (total * 0.14f);

        long now = System.currentTimeMillis();

        if (now - lastOutcomeAction < 1100) {
            return victoryArea > minimumArea ||
                    defeatArea > minimumArea;
        }

        if (victoryArea > minimumArea &&
                victoryArea >= defeatArea) {

            if (GameAccessibilityService.isReady()) {

                GameAccessibilityService.tap(
                        w / 2,
                        (int) (h * 0.76f)
                );

                lastOutcomeAction = now;
                return true;
            }
        }

        if (defeatArea > minimumArea) {

            if (GameAccessibilityService.isReady()) {

                GameAccessibilityService.tap(
                        w / 2,
                        (int) (h * 0.79f)
                );

                lastOutcomeAction = now;
                return true;
            }
        }

        return false;
    }

    private int largestComponent(
            boolean[][] mask,
            int width,
            int height) {

        boolean[][] visited =
                new boolean[height][width];

        int largest = 0;

        int[] queueX =
                new int[width * height];

        int[] queueY =
                new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                if (!mask[y][x] || visited[y][x]) {
                    continue;
                }

                int head = 0;
                int tail = 0;

                queueX[tail] = x;
                queueY[tail] = y;
                tail++;

                visited[y][x] = true;

                int count = 0;

                while (head < tail) {

                    int cx = queueX[head];
                    int cy = queueY[head];
                    head++;

                    count++;

                    int nx = cx + 1;
                    int ny = cy;

                    if (nx < width &&
                            mask[ny][nx] &&
                            !visited[ny][nx]) {

                        visited[ny][nx] = true;
                        queueX[tail] = nx;
                        queueY[tail] = ny;
                        tail++;
                    }

                    nx = cx - 1;

                    if (nx >= 0 &&
                            mask[ny][nx] &&
                            !visited[ny][nx]) {

                        visited[ny][nx] = true;
                        queueX[tail] = nx;
                        queueY[tail] = ny;
                        tail++;
                    }

                    nx = cx;
                    ny = cy + 1;

                    if (ny < height &&
                            mask[ny][nx] &&
                            !visited[ny][nx]) {

                        visited[ny][nx] = true;
                        queueX[tail] = nx;
                        queueY[tail] = ny;
                        tail++;
                    }

                    ny = cy - 1;

                    if (ny >= 0 &&
                            mask[ny][nx] &&
                            !visited[ny][nx]) {

                        visited[ny][nx] = true;
                        queueX[tail] = nx;
                        queueY[tail] = ny;
                        tail++;
                    }
                }

                if (count > largest) {
                    largest = count;
                }
            }
        }

        return largest;
    }

    private void steerToSafeGap(Bitmap bitmap) {

        if (!GameAccessibilityService.isReady()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastSteerAction < 220) {
            return;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        float playerX = findPlayerX(bitmap);

        if (playerX < 0) {
            playerX = w / 2f;
        }

        ArrayList<Gap> candidates =
                new ArrayList<>();

        int scanStart = (int) (h * 0.42f);
        int scanEnd = (int) (h * 0.86f);

        int minX = (int) (w * 0.05f);
        int maxX = (int) (w * 0.95f);

        for (int y = scanStart;
             y <= scanEnd;
             y += Math.max(8, h / 150)) {

            ArrayList<int[]> runs =
                    new ArrayList<>();

            boolean inRun = false;
            int runStart = 0;

            for (int x = minX;
                 x <= maxX;
                 x++) {

                int c = bitmap.getPixel(x, y);

                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                boolean blocked =
                        isTealPlatform(
                                hsv[0],
                                hsv[1],
                                hsv[2]
                        ) ||
                        isYellowDanger(
                                hsv[0],
                                hsv[1],
                                hsv[2]
                        );

                if (blocked && !inRun) {
                    inRun = true;
                    runStart = x;
                }

                if ((!blocked || x == maxX) && inRun) {

                    int runEnd =
                            blocked && x == maxX
                                    ? x
                                    : x - 1;

                    if (runEnd - runStart >=
                            Math.max(5, w / 100)) {

                        runs.add(
                                new int[]{
                                        runStart,
                                        runEnd
                                }
                        );
                    }

                    inRun = false;
                }
            }

            for (int i = 0;
                 i + 1 < runs.size();
                 i++) {

                int gapStart =
                        runs.get(i)[1] + 1;

                int gapEnd =
                        runs.get(i + 1)[0] - 1;

                int gapWidth =
                        gapEnd - gapStart + 1;

                if (gapWidth < (int) (w * 0.08f) ||
                        gapWidth > (int) (w * 0.55f)) {
                    continue;
                }

                int center =
                        (gapStart + gapEnd) / 2;

                float distance =
                        Math.abs(center - playerX);

                float score =
                        gapWidth -
                        distance * 0.80f;

                candidates.add(
                        new Gap(
                                score,
                                center,
                                gapWidth,
                                y
                        )
                );
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        Gap best = candidates.get(0);

        for (Gap g : candidates) {
            if (g.score > best.score) {
                best = g;
            }
        }

        float difference =
                best.center - playerX;

        float deadZone = w * 0.035f;

        if (Math.abs(difference) <= deadZone) {
            return;
        }

        int movement =
                (int) (w * 0.16f);

        int startX =
                clamp(
                        Math.round(playerX),
                        30,
                        w - 30
                );

        int endX =
                difference < 0
                        ? Math.max(
                                30,
                                startX - movement
                        )
                        : Math.min(
                                w - 30,
                                startX + movement
                        );

        int y =
                (int) (h * 0.48f);

        GameAccessibilityService.swipe(
                startX,
                y,
                endX,
                y,
                130
        );

        lastSteerAction = now;
    }

    private float findPlayerX(Bitmap bitmap) {

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int x1 = (int) (w * 0.15f);
        int x2 = (int) (w * 0.85f);

        int y1 = (int) (h * 0.20f);
        int y2 = (int) (h * 0.42f);

        boolean[][] mask =
                new boolean[y2 - y1][x2 - x1];

        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {

                int c = bitmap.getPixel(x, y);

                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                int hue = hsv[0];
                int sat = hsv[1];
                int val = hsv[2];

                mask[y - y1][x - x1] =
                        (hue < 30 || hue > 170) &&
                        sat > 100 &&
                        val > 100;
            }
        }

        int areaW = x2 - x1;
        int areaH = y2 - y1;

        boolean[][] visited =
                new boolean[areaH][areaW];

        int[] qx =
                new int[areaW * areaH];

        int[] qy =
                new int[areaW * areaH];

        int bestArea = 0;
        float bestCenter = -1;

        for (int y = 0; y < areaH; y++) {
            for (int x = 0; x < areaW; x++) {

                if (!mask[y][x] || visited[y][x]) {
                    continue;
                }

                int head = 0;
                int tail = 0;

                qx[tail] = x;
                qy[tail] = y;
                tail++;

                visited[y][x] = true;

                int count = 0;
                long sumX = 0;

                while (head < tail) {

                    int cx = qx[head];
                    int cy = qy[head];
                    head++;

                    count++;
                    sumX += cx;

                    int nx = cx + 1;

                    if (nx < areaW &&
                            mask[cy][nx] &&
                            !visited[cy][nx]) {

                        visited[cy][nx] = true;
                        qx[tail] = nx;
                        qy[tail] = cy;
                        tail++;
                    }

                    nx = cx - 1;

                    if (nx >= 0 &&
                            mask[cy][nx] &&
                            !visited[cy][nx]) {

                        visited[cy][nx] = true;
                        qx[tail] = nx;
                        qy[tail] = cy;
                        tail++;
                    }

                    int ny = cy + 1;

                    if (ny < areaH &&
                            mask[ny][cx] &&
                            !visited[ny][cx]) {

                        visited[ny][cx] = true;
                        qx[tail] = cx;
                        qy[tail] = ny;
                        tail++;
                    }

                    ny = cy - 1;

                    if (ny >= 0 &&
                            mask[ny][cx] &&
                            !visited[ny][cx]) {

                        visited[ny][cx] = true;
                        qx[tail] = cx;
                        qy[tail] = ny;
                        tail++;
                    }
                }

                if (count > bestArea) {
                    bestArea = count;
                    bestCenter =
                            x1 + ((float) sumX / count);
                }
            }
        }

        return bestArea >= 300
                ? bestCenter
                : -1;
    }

    private boolean isTealPlatform(
            int hue,
            int sat,
            int val) {

        return hue >= 80 &&
                hue <= 110 &&
                sat >= 80 &&
                val >= 60;
    }

    private boolean isYellowDanger(
            int hue,
            int sat,
            int val) {

        return hue >= 18 &&
                hue <= 45 &&
                sat >= 120 &&
                val >= 120;
    }

    private int[] rgbToHsv(
            int r,
            int g,
            int b) {

        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;

        float max =
                Math.max(
                        rf,
                        Math.max(gf, bf)
                );

        float min =
                Math.min(
                        rf,
                        Math.min(gf, bf)
                );

        float delta = max - min;

        float hue;

        if (delta == 0) {
            hue = 0;
        } else if (max == rf) {
            hue =
                    60f *
                    (((gf - bf) / delta) % 6f);
        } else if (max == gf) {
            hue =
                    60f *
                    (((bf - rf) / delta) + 2f);
        } else {
            hue =
                    60f *
                    (((rf - gf) / delta) + 4f);
        }

        if (hue < 0) {
            hue += 360f;
        }

        float saturation =
                max == 0
                        ? 0
                        : delta / max;

        return new int[]{
                Math.round(hue / 2f),
                Math.round(saturation * 255f),
                Math.round(max * 255f)
        };
    }

    private int clamp(
            int value,
            int min,
            int max) {

        return Math.max(
                min,
                Math.min(max, value)
        );
    }

    private static class Gap {
        final float score;
        final int center;
        final int width;
        final int y;

        Gap(
                float score,
                int center,
                int width,
                int y) {

            this.score = score;
            this.center = center;
            this.width = width;
            this.y = y;
        }
    }

    private Notification buildNotification() {

        return new Notification.Builder(
                this,
                "autoplayer"
        )
                .setContentTitle(
                        "Vluck Auto Player"
                )
                .setContentText(
                        "Auto play is running"
                )
                .setSmallIcon(
                        android.R.drawable.ic_media_play
                )
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            "autoplayer",
                            "Auto Player",
                            NotificationManager.IMPORTANCE_LOW
                    );

            getSystemService(
                    NotificationManager.class
            ).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        if (imageReader != null) {
            imageReader.close();
        }

        if (projection != null) {
            projection.stop();
        }

        if (thread != null) {
            thread.quitSafely();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
