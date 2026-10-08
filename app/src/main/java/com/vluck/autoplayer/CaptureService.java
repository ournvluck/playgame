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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * V3 game controller.
 *
 * Game model used here:
 * - The character stays close to the screen center.
 * - The tower/platforms rotate horizontally.
 * - The character automatically jumps/falls.
 * - The safe route is a GAP, not a platform surface and never the yellow area.
 * - During loading, the controller does nothing and waits for the board.
 */
public class CaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static volatile boolean running = false;

    /*
     * If the first test shows that a swipe moves the gap in the opposite
     * direction, the controller automatically learns the direction after
     * observing the next frame. This is only the initial guess.
     */
    private int controlSign = 1;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;

    private long lastFrame = 0;
    private long lastSteerAction = 0;
    private long lastOutcomeAction = 0;
    private long lastBoardSeen = 0;

    private float previousTargetX = -1;
    private float previousGapWidth = -1;
    private float previousCenterDistance = -1;
    private int previousSteerDirection = 0;
    private long previousSteerTime = 0;

    private int stableBoardFrames = 0;

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
        if (intent == null) {
            return START_STICKY;
        }

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

            DisplayMetrics dm = getResources().getDisplayMetrics();
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

        if (now - lastFrame < 90) {
            return;
        }
        lastFrame = now;

        Image image = null;
        Bitmap bitmap = null;

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
            int rowPadding = rowStride - pixelStride * width;

            bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(plane.getBuffer());

            /*
             * Loading screen / blank / transition:
             * do absolutely nothing and wait for a real game board.
             */
            if (!hasGameBoard(bitmap)) {
                stableBoardFrames = 0;
                return;
            }

            lastBoardSeen = now;
            stableBoardFrames++;

            /* Don't steer on the first frame after loading. */
            if (stableBoardFrames < 3) {
                return;
            }

            /* Victory/defeat is checked before normal steering. */
            if (handleOutcome(bitmap)) {
                return;
            }

            steerToNextSafeGap(bitmap);

        } catch (Throwable ignored) {
            // Keep the service alive if one captured frame is malformed.
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (image != null) {
                image.close();
            }
        }
    }

    /**
     * The supplied loading screenshot is mostly black with a white loading
     * indicator and contains no teal game platforms. We therefore use the
     * presence of real teal platform pixels as the board-ready signal.
     */
    private boolean hasGameBoard(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int x0 = (int) (w * 0.05f);
        int x1 = (int) (w * 0.95f);
        int y0 = (int) (h * 0.25f);
        int y1 = (int) (h * 0.90f);

        int teal = 0;
        int samples = 0;

        int stepX = Math.max(3, w / 180);
        int stepY = Math.max(3, h / 320);

        for (int y = y0; y < y1; y += stepY) {
            for (int x = x0; x < x1; x += stepX) {
                int c = bitmap.getPixel(x, y);
                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );
                if (isTealPlatform(hsv[0], hsv[1], hsv[2])) {
                    teal++;
                }
                samples++;
            }
        }

        /* Around 0.25% of samples is enough to reject the black loading page. */
        return samples > 0 && teal > Math.max(80, samples / 380);
    }

    /**
     * Outcome detection is based on the actual Continue/Restart button
     * colours, not generic purple/blue pixels. This avoids treating normal
     * game graphics as a result screen.
     */
    private boolean handleOutcome(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int cx = w / 2;
        int cy = (int) (h * 0.77f);

        int radiusX = (int) (w * 0.20f);
        int radiusY = (int) (h * 0.075f);

        int blueButton = 0;
        int purpleButton = 0;
        int samples = 0;

        for (int y = Math.max(0, cy - radiusY);
             y < Math.min(h, cy + radiusY);
             y += Math.max(2, h / 600)) {

            for (int x = Math.max(0, cx - radiusX);
                 x < Math.min(w, cx + radiusX);
                 x += Math.max(2, w / 260)) {

                int c = bitmap.getPixel(x, y);
                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                int hue = hsv[0];
                int sat = hsv[1];
                int val = hsv[2];

                if (sat > 55 && val > 100) {
                    /* Continue button: light blue / blue */
                    if (hue >= 90 && hue <= 123) {
                        blueButton++;
                    }
                    /* Restart button: lavender / purple */
                    if (hue >= 124 && hue <= 170) {
                        purpleButton++;
                    }
                }
                samples++;
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastOutcomeAction < 1400) {
            return blueButton > samples * 0.02f ||
                    purpleButton > samples * 0.02f;
        }

        int threshold = Math.max(80, (int) (samples * 0.035f));

        if (blueButton > threshold &&
                blueButton > purpleButton * 1.20f) {

            if (GameAccessibilityService.isReady()) {
                GameAccessibilityService.tap(
                        cx,
                        (int) (h * 0.76f)
                );
                lastOutcomeAction = now;
                resetSteeringMemory();
                return true;
            }
        }

        if (purpleButton > threshold &&
                purpleButton > blueButton * 1.20f) {

            if (GameAccessibilityService.isReady()) {
                GameAccessibilityService.tap(
                        cx,
                        (int) (h * 0.79f)
                );
                lastOutcomeAction = now;
                resetSteeringMemory();
                return true;
            }
        }

        return false;
    }

    /**
     * Core game logic.
     *
     * Instead of asking "where is the player?", this version assumes the
     * character is the fixed reference point in the centre. We locate the
     * FIRST real platform below the character, identify all safe gaps in that
     * platform, and rotate the tower until a safe gap is centred.
     */
    private void steerToNextSafeGap(Bitmap bitmap) {
        if (!GameAccessibilityService.isReady()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastSteerAction < 280) {
            learnFromPreviousSteer(bitmap);
            return;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int centerX = w / 2;

        int playerY = estimatePlayerY(bitmap);
        if (playerY < 0) {
            /* The board is visible but the character is not confidently found. */
            playerY = (int) (h * 0.30f);
        }

        PlatformLine platform = findNextPlatform(bitmap, playerY);
        if (platform == null) {
            return;
        }

        List<Gap> safeGaps = findSafeGaps(bitmap, platform.y, centerX);
        if (safeGaps.isEmpty()) {
            return;
        }

        Gap best = chooseBestGap(safeGaps, centerX);
        if (best == null) {
            return;
        }

        float centerDistance = Math.abs(best.center - centerX);

        /*
         * If the centre already lies inside a wide safe gap, do NOT move.
         * This is the critical rule: never rotate off a safe opening just
         * because a different opening looks attractive.
         */
        if (best.start <= centerX && best.end >= centerX &&
                best.width >= w * 0.09f) {
            previousTargetX = best.center;
            previousGapWidth = best.width;
            previousCenterDistance = centerDistance;
            return;
        }

        /* If there is a safe gap close enough to centre, make only a tiny move. */
        float deadZone = w * 0.035f;
        if (centerDistance <= deadZone) {
            return;
        }

        int direction = best.center > centerX ? 1 : -1;

        /* Learn whether screen motion follows or opposes our initial sign. */
        int swipeDirection = direction * controlSign;

        int distance = (int) Math.min(
                w * 0.20f,
                Math.max(w * 0.075f, centerDistance * 0.65f)
        );

        int startX = centerX;
        int endX = centerX + swipeDirection * distance;

        endX = clamp(endX, 40, w - 40);

        /* Horizontal drag is the game's tower rotation control. */
        GameAccessibilityService.swipe(
                startX,
                (int) (h * 0.48f),
                endX,
                (int) (h * 0.48f),
                115
        );

        lastSteerAction = now;
        previousTargetX = best.center;
        previousGapWidth = best.width;
        previousCenterDistance = centerDistance;
        previousSteerDirection = direction;
        previousSteerTime = now;
    }

    /**
     * After a steering gesture, look for the same/nearest gap. If it moved in
     * the wrong direction, invert the control sign. This compensates for the
     * game's drag convention without requiring the user to configure it.
     */
    private void learnFromPreviousSteer(Bitmap bitmap) {
        if (previousTargetX < 0 || previousSteerDirection == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - previousSteerTime < 260 ||
                now - previousSteerTime > 850) {
            return;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int centerX = w / 2;

        PlatformLine platform = findNextPlatform(
                bitmap,
                (int) (h * 0.28f)
        );

        if (platform == null) {
            return;
        }

        List<Gap> gaps = findSafeGaps(
                bitmap,
                platform.y,
                centerX
        );

        Gap nearest = nearestGap(gaps, previousTargetX);
        if (nearest == null) {
            return;
        }

        float movement = nearest.center - previousTargetX;

        /* The desired gap was supposed to move toward the centre. */
        boolean wantedLeft = previousTargetX < centerX;
        boolean movedLeft = movement < -w * 0.01f;
        boolean movedRight = movement > w * 0.01f;

        boolean movedTowardCenter =
                (wantedLeft && movedRight) ||
                (!wantedLeft && movedLeft);

        if ((movedLeft || movedRight) && !movedTowardCenter) {
            controlSign *= -1;
        }

        previousSteerDirection = 0;
    }

    /** Find the first strong platform band below the character. */
    private PlatformLine findNextPlatform(Bitmap bitmap, int playerY) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int start = clamp(
                playerY + Math.max(35, h / 50),
                0,
                h - 1
        );
        int end = (int) (h * 0.92f);

        int minOccupied = Math.max(10, (int) (w * 0.12f));

        /* Search from top to bottom: first strong band is the next platform. */
        for (int y = start; y < end; y += Math.max(4, h / 300)) {
            int occupied = countPlatformPixels(bitmap, y);

            if (occupied < minOccupied) {
                continue;
            }

            int bestY = y;
            int bestCount = occupied;

            int window = Math.max(8, h / 100);
            for (int yy = y + 4;
                 yy <= Math.min(end, y + window);
                 yy += 4) {

                int count = countPlatformPixels(bitmap, yy);
                if (count > bestCount) {
                    bestCount = count;
                    bestY = yy;
                }
            }

            if (bestCount >= minOccupied) {
                return new PlatformLine(bestY, bestCount);
            }
        }

        return null;
    }

    private int countPlatformPixels(Bitmap bitmap, int y) {
        int w = bitmap.getWidth();
        int x0 = (int) (w * 0.06f);
        int x1 = (int) (w * 0.94f);
        int count = 0;

        int step = Math.max(2, w / 360);
        for (int x = x0; x < x1; x += step) {
            int c = bitmap.getPixel(x, y);
            int[] hsv = rgbToHsv(
                    Color.red(c),
                    Color.green(c),
                    Color.blue(c)
            );
            if (isTealPlatform(hsv[0], hsv[1], hsv[2]) ||
                    isYellowDanger(hsv[0], hsv[1], hsv[2])) {
                count += step;
            }
        }
        return count;
    }

    /**
     * Convert one horizontal platform line into safe gaps.
     * Teal and yellow are both treated as occupied. Only non-platform space
     * between real platform runs is a candidate gap.
     */
    private List<Gap> findSafeGaps(
            Bitmap bitmap,
            int y,
            int centerX) {

        int w = bitmap.getWidth();
        int minX = (int) (w * 0.06f);
        int maxX = (int) (w * 0.94f);
        int step = Math.max(2, w / 420);

        ArrayList<Run> runs = new ArrayList<>();
        boolean inRun = false;
        int runStart = 0;

        for (int x = minX; x <= maxX; x += step) {
            boolean occupied = isPlatformPixel(bitmap, x, y);

            if (occupied && !inRun) {
                inRun = true;
                runStart = x;
            }

            if ((!occupied || x + step > maxX) && inRun) {
                int runEnd = occupied ? x : x - step;
                if (runEnd - runStart >= Math.max(8, w / 90)) {
                    runs.add(new Run(runStart, runEnd));
                }
                inRun = false;
            }
        }

        ArrayList<Gap> gaps = new ArrayList<>();

        for (int i = 0; i + 1 < runs.size(); i++) {
            int start = runs.get(i).end + 1;
            int end = runs.get(i + 1).start - 1;
            int width = end - start + 1;

            if (width < w * 0.035f || width > w * 0.55f) {
                continue;
            }

            /* Extra clearance for the character: do not skim an edge. */
            int clearance = Math.max(10, (int) (w * 0.025f));
            int safeStart = start + clearance;
            int safeEnd = end - clearance;
            int safeWidth = safeEnd - safeStart + 1;

            if (safeWidth < w * 0.025f) {
                continue;
            }

            gaps.add(new Gap(
                    safeStart,
                    safeEnd,
                    safeWidth,
                    (safeStart + safeEnd) / 2
            ));
        }

        /* Include edge gaps only if they are large enough. */
        if (!runs.isEmpty()) {
            Run first = runs.get(0);
            if (first.start - minX >= w * 0.08f) {
                gaps.add(new Gap(
                        minX,
                        first.start - 1,
                        first.start - minX,
                        (minX + first.start - 1) / 2
                ));
            }

            Run last = runs.get(runs.size() - 1);
            if (maxX - last.end >= w * 0.08f) {
                gaps.add(new Gap(
                        last.end + 1,
                        maxX,
                        maxX - last.end,
                        (last.end + 1 + maxX) / 2
                ));
            }
        }

        return gaps;
    }

    private boolean isPlatformPixel(Bitmap bitmap, int x, int y) {
        int c = bitmap.getPixel(
                clamp(x, 0, bitmap.getWidth() - 1),
                clamp(y, 0, bitmap.getHeight() - 1)
        );

        int[] hsv = rgbToHsv(
                Color.red(c),
                Color.green(c),
                Color.blue(c)
        );

        return isTealPlatform(hsv[0], hsv[1], hsv[2]) ||
                isYellowDanger(hsv[0], hsv[1], hsv[2]);
    }

    private Gap chooseBestGap(List<Gap> gaps, int centerX) {
        if (gaps.isEmpty()) {
            return null;
        }

        Gap best = null;
        float bestScore = -Float.MAX_VALUE;

        for (Gap g : gaps) {
            boolean containsCenter =
                    g.start <= centerX && g.end >= centerX;

            float distance = Math.abs(g.center - centerX);
            float widthBonus = Math.min(g.width, 0.25f * 10000f);

            float score =
                    (containsCenter ? 100000f : 0f) +
                    widthBonus * 2f -
                    distance * 1.7f;

            if (score > bestScore) {
                bestScore = score;
                best = g;
            }
        }

        return best;
    }

    private Gap nearestGap(List<Gap> gaps, float x) {
        Gap best = null;
        float distance = Float.MAX_VALUE;

        for (Gap g : gaps) {
            float d = Math.abs(g.center - x);
            if (d < distance) {
                distance = d;
                best = g;
            }
        }
        return best;
    }

    /** Blue spikes/character are more reliable than the orange face. */
    private int estimatePlayerY(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int x0 = (int) (w * 0.30f);
        int x1 = (int) (w * 0.70f);
        int y0 = (int) (h * 0.12f);
        int y1 = (int) (h * 0.50f);

        long sumY = 0;
        long count = 0;

        int step = Math.max(2, w / 360);

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = bitmap.getPixel(x, y);
                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                /* Strong blue, but not the teal platform hue. */
                if (hsv[0] >= 105 && hsv[0] <= 145 &&
                        hsv[1] >= 110 && hsv[2] >= 45) {
                    count++;
                    sumY += y;
                }
            }
        }

        if (count < 25) {
            return -1;
        }

        return (int) (sumY / count);
    }

    private void resetSteeringMemory() {
        previousTargetX = -1;
        previousGapWidth = -1;
        previousCenterDistance = -1;
        previousSteerDirection = 0;
        previousSteerTime = 0;
    }

    private boolean isTealPlatform(int hue, int sat, int val) {
        return hue >= 82 && hue <= 112 && sat >= 70 && val >= 45;
    }

    private boolean isYellowDanger(int hue, int sat, int val) {
        return hue >= 18 && hue <= 48 && sat >= 115 && val >= 120;
    }

    private int[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;

        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        float hue;
        if (delta == 0) {
            hue = 0;
        } else if (max == rf) {
            hue = 60f * (((gf - bf) / delta) % 6f);
        } else if (max == gf) {
            hue = 60f * (((bf - rf) / delta) + 2f);
        } else {
            hue = 60f * (((rf - gf) / delta) + 4f);
        }

        if (hue < 0) {
            hue += 360f;
        }

        float saturation = max == 0 ? 0 : delta / max;

        return new int[]{
                Math.round(hue / 2f),
                Math.round(saturation * 255f),
                Math.round(max * 255f)
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class PlatformLine {
        final int y;
        final int strength;
        PlatformLine(int y, int strength) {
            this.y = y;
            this.strength = strength;
        }
    }

    private static class Run {
        final int start;
        final int end;
        Run(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class Gap {
        final int start;
        final int end;
        final int width;
        final int center;

        Gap(int start, int end, int width, int center) {
            this.start = start;
            this.end = end;
            this.width = width;
            this.center = center;
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, "autoplayer")
                .setContentTitle("Vluck Auto Player")
                .setContentText("Auto play is running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    "autoplayer",
                    "Auto Player",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
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
