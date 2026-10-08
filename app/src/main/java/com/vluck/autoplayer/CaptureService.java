package com.vluck.autoplayer;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.graphics.Bitmap;
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
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * V4 controller for the Helix-style game shown in the supplied recording.
 *
 * Main change from V3:
 * The controller does not only inspect the next platform.  It looks several
 * platforms downward and chooses a horizontal angle that avoids YELLOW on
 * as many upcoming platforms as possible.  Teal is safe; empty space is
 * also safe.  This is important because the ball can pass one safe opening
 * and then hit yellow on the following platform.
 */
public class CaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static volatile boolean running = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;

    private long lastFrame;
    private long lastSteer;
    private long lastOutcome;

    /* Initial guess; V4 learns the actual drag direction from screen feedback. */
    private int controlSign = 1;

    private int pendingSwipeSign = 0;
    private float pendingBeforeScore = Float.NaN;
    private long pendingSwipeAt = 0;
    private int reversalCount = 0;

    private int stableBoardFrames = 0;
    private long frameCount = 0;
    private long actionCount = 0;
    private long lastNotificationUpdate = 0;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, buildNotification());
        }

        thread = new HandlerThread("vluck-screen-analysis");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

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
                    dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );
        }

        return START_STICKY;
    }

    private void analyze(ImageReader reader) {
        frameCount++;
        long now = System.currentTimeMillis();

        if (now - lastFrame < 75) {
            return;
        }
        lastFrame = now;

        Image image = null;
        Bitmap bitmap = null;

        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

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

            if (!hasGameBoard(bitmap)) {
                stableBoardFrames = 0;
                resetControlMemory();
                return;
            }

            stableBoardFrames++;

            /* Give the game a few frames after Loading... disappears. */
            if (stableBoardFrames < 3) return;

            if (handleOutcome(bitmap)) {
                return;
            }

            controlGame(bitmap);

        } catch (Throwable ignored) {
            // Never kill the capture service because one frame is malformed.
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (image != null) image.close();
        }
    }

    private boolean hasGameBoard(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        int x0 = (int)(w * 0.05f);
        int x1 = (int)(w * 0.95f);
        int y0 = (int)(h * 0.20f);
        int y1 = (int)(h * 0.92f);

        int teal = 0;
        int yellow = 0;
        int samples = 0;

        int sx = Math.max(4, w / 180);
        int sy = Math.max(4, h / 300);

        for (int y = y0; y < y1; y += sy) {
            for (int x = x0; x < x1; x += sx) {
                int c = b.getPixel(x, y);
                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                if (isTeal(hsv[0], hsv[1], hsv[2])) teal++;
                if (isYellow(hsv[0], hsv[1], hsv[2])) yellow++;
                samples++;
            }
        }

        /*
         * Loading is black and has neither the teal platform texture nor the
         * yellow hazard.  Either color is enough to consider the board live.
         */
        return samples > 0 &&
                (teal + yellow) > Math.max(100, samples / 300);
    }

    private boolean handleOutcome(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        /*
         * Both Continue and Restart are the large centre button shown in the
         * recording.  Continue is blue; Restart is lavender/purple.
         */
        int cx = w / 2;
        int cy = (int)(h * 0.78f);

        int rx = (int)(w * 0.19f);
        int ry = (int)(h * 0.085f);

        int blue = 0;
        int purple = 0;
        int samples = 0;

        int stepX = Math.max(4, w / 220);
        int stepY = Math.max(4, h / 360);

        for (int y = Math.max(0, cy - ry);
             y < Math.min(h, cy + ry);
             y += stepY) {

            for (int x = Math.max(0, cx - rx);
                 x < Math.min(w, cx + rx);
                 x += stepX) {

                int c = b.getPixel(x, y);
                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                int hue = hsv[0];
                int sat = hsv[1];
                int val = hsv[2];

                if (sat > 45 && val > 100) {
                    if (hue >= 85 && hue <= 125) blue++;
                    if (hue >= 125 && hue <= 175) purple++;
                }

                samples++;
            }
        }

        long now = System.currentTimeMillis();

        if (now - lastOutcome < 1400) {
            return blue > samples * 0.02f ||
                    purple > samples * 0.02f;
        }

        int threshold = Math.max(70, (int)(samples * 0.025f));

        if (blue > threshold && blue > purple * 1.15f) {
            if (GameAccessibilityService.isReady()) {
                GameAccessibilityService.tap(
                        cx,
                        (int)(h * 0.77f)
                );
                lastOutcome = now;
                actionCount++;
                updateNotification();
                resetControlMemory();
                return true;
            }
        }

        if (purple > threshold && purple > blue * 1.15f) {
            if (GameAccessibilityService.isReady()) {
                GameAccessibilityService.tap(
                        cx,
                        (int)(h * 0.79f)
                );
                lastOutcome = now;
                actionCount++;
                updateNotification();
                resetControlMemory();
                return true;
            }
        }

        return false;
    }

    private void controlGame(Bitmap b) {
        if (!GameAccessibilityService.isReady()) return;

        long now = System.currentTimeMillis();

        /*
         * Learn the drag direction after every real gesture.  We compare the
         * danger score before/after the gesture.  If it became worse, reverse
         * the control direction automatically.
         */
        if (pendingSwipeSign != 0 &&
                now - pendingSwipeAt >= 220 &&
                now - pendingSwipeAt <= 900) {

            PlayerAndPlan plan = analyzePlan(b);

            if (plan != null && !Float.isNaN(pendingBeforeScore)) {
                if (plan.dangerScore > pendingBeforeScore + 0.15f) {
                    controlSign *= -1;
                    reversalCount++;
                }

                pendingSwipeSign = 0;

                if (reversalCount >= 2) {
                    reversalCount = 0;
                }
            }
        }

        if (now - lastSteer < 185) return;

        PlayerAndPlan plan = analyzePlan(b);
        if (plan == null) return;

        /*
         * dangerScore is 0 when the current centre angle is safe across the
         * visible upcoming platforms.  A non-zero score means yellow is in
         * the landing corridor on one or more upcoming floors.
         */
        if (plan.bestTargetX < 0) return;

        int center = b.getWidth() / 2;
        int delta = plan.bestTargetX - center;

        // Even when the current centre is safe, move toward a deeper safe
        // corridor if it is materially different.  The game keeps falling
        // through successive platforms, so waiting on a currently-safe
        // opening can still put the character onto yellow below.
        if (Math.abs(delta) < b.getWidth() * 0.06f) {
            return;
        }

        /*
         * If a target is very close, do nothing.  The character has width and
         * the tower can continue rotating slightly between frames.
         */
        if (Math.abs(delta) < b.getWidth() * 0.045f) {
            return;
        }

        int direction = delta > 0 ? 1 : -1;

        /*
         * The feature at bestTargetX must move toward screen centre.
         * controlSign converts desired screen movement to the game's drag
         * convention.
         */
        int swipeDirection = direction * controlSign;

        int distance = (int)Math.min(
                b.getWidth() * 0.24f,
                Math.max(
                        b.getWidth() * 0.07f,
                        Math.abs(delta) * 0.58f
                )
        );

        int startX = center;
        int endX = clamp(
                center + swipeDirection * distance,
                50,
                b.getWidth() - 50
        );

        GameAccessibilityService.swipe(
                startX,
                (int)(b.getHeight() * 0.50f),
                endX,
                (int)(b.getHeight() * 0.50f),
                105
        );

        lastSteer = now;
        actionCount++;
        updateNotification();
        pendingSwipeSign = swipeDirection;
        pendingBeforeScore = plan.dangerScore;
        pendingSwipeAt = now;
    }

    /**
     * Look several platforms below the character and choose a horizontal
     * angle which does not intersect yellow on the upcoming platforms.
     */
    private PlayerAndPlan analyzePlan(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int center = w / 2;

        // The character stays close to the centre of the screen.  Do not make
        // the whole controller depend on detecting the orange face: bubbles,
        // animation and scaling can temporarily hide it.
        int playerY = (int)(h * 0.30f);

        boolean[][] masks = buildMasks(b);

        boolean[] platformMask = masks[0];
        boolean[] yellowMask = masks[1];

        float[] rowStrength = new float[h];

        int x0 = (int)(w * 0.055f);
        int x1 = (int)(w * 0.945f);

        /*
         * Instead of reading one noisy scan line, average a vertical band.
         * This makes the platform detector stable against texture and bubbles.
         */
        for (int y = Math.max(0, playerY + 70);
             y < Math.min(h, (int)(h * 0.94f));
             y++) {

            int count = 0;

            for (int x = x0; x < x1; x += Math.max(3, w / 360)) {
                if (platformMask[y * w + x]) count++;
            }

            rowStrength[y] =
                    count /
                    (float)Math.max(
                            1,
                            (x1 - x0) / Math.max(3, w / 360)
                    );
        }

        ArrayList<Integer> peaks = new ArrayList<>();

        for (int y = playerY + 70;
             y < Math.min(h * 0.94f, playerY + (int)(h * 0.70f));
             y++) {

            if (rowStrength[y] < 0.16f) continue;

            boolean localMax = true;

            for (int d = 1; d <= 12; d++) {
                int ya = y - d;
                int yb = y + d;

                if (ya >= 0 && rowStrength[ya] > rowStrength[y]) {
                    localMax = false;
                    break;
                }

                if (yb < h && rowStrength[yb] > rowStrength[y]) {
                    localMax = false;
                    break;
                }
            }

            if (!localMax) continue;

            if (peaks.isEmpty() ||
                    y - peaks.get(peaks.size() - 1) > 100) {
                peaks.add(y);
            } else {
                int last = peaks.size() - 1;
                if (rowStrength[y] > rowStrength[peaks.get(last)]) {
                    peaks.set(last, y);
                }
            }
        }

        if (peaks.isEmpty()) return null;

        /*
         * Merge multiple local peaks belonging to the same thick platform.
         */
        ArrayList<Integer> bands = new ArrayList<>();

        for (int p : peaks) {
            if (bands.isEmpty() ||
                    p - bands.get(bands.size() - 1) > 175) {
                bands.add(p);
            } else {
                int last = bands.size() - 1;
                if (rowStrength[p] > rowStrength[bands.get(last)]) {
                    bands.set(last, p);
                }
            }
        }

        if (bands.isEmpty()) return null;

        /*
         * Only the next few platforms matter.  More distant floors are
         * intentionally ignored because the tower can change before then.
         */
        int platformCount = Math.min(5, bands.size());

        ArrayList<Interval> yellowIntervals =
                new ArrayList<>();

        ArrayList<ArrayList<Interval>> perPlatform =
                new ArrayList<>();

        for (int i = 0; i < platformCount; i++) {

            int y = bands.get(i);

            ArrayList<Interval> intervals =
                    getYellowIntervals(
                            yellowMask,
                            w,
                            h,
                            y
                    );

            perPlatform.add(intervals);
        }

        /*
         * Try many possible horizontal angles.  A point is unsafe when the
         * character-sized corridor around it overlaps yellow.
         */
        int halfCharacter =
                Math.max(28, (int)(w * 0.038f));

        ArrayList<Integer> candidates =
                new ArrayList<>();

        for (int x = 70; x <= w - 70; x += Math.max(8, w / 90)) {
            candidates.add(x);
        }

        for (ArrayList<Interval> intervals : perPlatform) {
            for (Interval in : intervals) {
                candidates.add(
                        clamp(
                                in.start - halfCharacter - 10,
                                70,
                                w - 70
                        )
                );

                candidates.add(
                        clamp(
                                in.end + halfCharacter + 10,
                                70,
                                w - 70
                        )
                );
            }
        }

        int bestX = -1;
        float bestScore = Float.MAX_VALUE;
        float currentScore = dangerAt(
                center,
                perPlatform,
                halfCharacter,
                platformCount
        );

        for (int x : candidates) {

            float danger =
                    dangerAt(
                            x,
                            perPlatform,
                            halfCharacter,
                            platformCount
                    );

            /*
             * Earlier platforms get higher weight because they will be hit
             * first.  Still reward a target that is safe deeper down.
             */
            float weighted = danger;

            if (danger == 0) {
                weighted = 0;
            }

            float distancePenalty =
                    Math.abs(x - center) /
                    (float)w;

            /*
             * If two targets have identical safety, stay close to centre.
             * But safety dominates strongly.
             */
            float score =
                    weighted * 1000f +
                    distancePenalty;

            if (score < bestScore) {
                bestScore = score;
                bestX = x;
            }
        }

        if (bestX < 0) return null;

        return new PlayerAndPlan(
                playerY,
                bestX,
                currentScore
        );
    }

    /**
     * danger is the number of upcoming platforms whose yellow interval
     * intersects the character corridor.
     */
    private float dangerAt(
            int x,
            ArrayList<ArrayList<Interval>> perPlatform,
            int halfCharacter,
            int count) {

        float score = 0;

        for (int i = 0; i < count; i++) {

            ArrayList<Interval> intervals =
                    perPlatform.get(i);

            boolean hit = false;

            for (Interval in : intervals) {
                if (x + halfCharacter >= in.start &&
                        x - halfCharacter <= in.end) {
                    hit = true;
                    break;
                }
            }

            if (hit) {
                /*
                 * The first platform is most urgent.
                 */
                score +=
                        (count - i) /
                        (float)count;
            }
        }

        return score;
    }

    private ArrayList<Interval> getYellowIntervals(
            boolean[] yellow,
            int w,
            int h,
            int centerY) {

        int halfBand = 55;
        int x0 = (int)(w * 0.055f);
        int x1 = (int)(w * 0.945f);

        float[] score = new float[x1 - x0];

        for (int x = x0; x < x1; x++) {

            int count = 0;
            int total = 0;

            for (int y = Math.max(0, centerY - halfBand);
                 y <= Math.min(h - 1, centerY + halfBand);
                 y += 4) {

                total++;

                if (yellow[y * w + x]) {
                    count++;
                }
            }

            score[x - x0] =
                    count / (float)Math.max(1, total);
        }

        /*
         * Yellow sectors are wide.  A little horizontal smoothing makes the
         * result insensitive to texture, bubbles and anti-aliasing.
         */
        float[] smooth = new float[score.length];

        int radius = Math.max(4, w / 220);

        for (int i = 0; i < score.length; i++) {
            int a = Math.max(0, i - radius);
            int z = Math.min(score.length - 1, i + radius);

            float sum = 0;
            for (int j = a; j <= z; j++) sum += score[j];

            smooth[i] = sum / (z - a + 1);
        }

        ArrayList<Interval> result = new ArrayList<>();

        boolean inside = false;
        int start = 0;

        for (int i = 0; i < smooth.length; i++) {

            boolean yes = smooth[i] >= 0.16f;

            if (yes && !inside) {
                inside = true;
                start = i;
            }

            if ((!yes || i == smooth.length - 1) && inside) {

                int end =
                        (yes && i == smooth.length - 1)
                                ? i
                                : i - 1;

                int sx = start + x0;
                int ex = end + x0;

                if (ex - sx >= w * 0.035f) {
                    result.add(new Interval(sx, ex));
                }

                inside = false;
            }
        }

        return result;
    }

    private int findPlayerY(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        /*
         * The recording clearly shows an orange face under a blue spiked
         * shell.  Orange is much less confused with the teal platforms than
         * blue, so use the face for vertical tracking.
         */
        int x0 = (int)(w * 0.34f);
        int x1 = (int)(w * 0.66f);
        int y0 = (int)(h * 0.10f);
        int y1 = (int)(h * 0.58f);

        boolean[] mask = new boolean[w * h];

        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {

                int c = b.getPixel(x, y);

                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                /*
                 * Orange face:
                 * hue 5..22 in Android/OpenCV-style HSV (0..180)
                 */
                if (hsv[0] >= 5 &&
                        hsv[0] <= 22 &&
                        hsv[1] >= 100 &&
                        hsv[2] >= 80) {

                    mask[y * w + x] = true;
                }
            }
        }

        /*
         * Use row density.  The face creates a much stronger orange cluster
         * than bubbles or UI elements.
         */
        int bestY = -1;
        int bestCount = 0;

        for (int y = y0; y < y1; y += 4) {

            int count = 0;

            for (int x = x0; x < x1; x += 4) {
                if (mask[y * w + x]) count++;
            }

            if (count > bestCount) {
                bestCount = count;
                bestY = y;
            }
        }

        if (bestY < 0 || bestCount < 8) {
            return -1;
        }

        /*
         * Average nearby strong orange rows to reduce jitter.
         */
        int sum = 0;
        int n = 0;

        for (int y = Math.max(y0, bestY - 50);
             y <= Math.min(y1 - 1, bestY + 50);
             y += 2) {

            int count = 0;

            for (int x = x0; x < x1; x += 3) {
                if (mask[y * w + x]) count++;
            }

            if (count >= Math.max(3, bestCount / 4)) {
                sum += y;
                n++;
            }
        }

        return n > 0 ? sum / n : bestY;
    }

    private boolean[][] buildMasks(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        boolean[] platform = new boolean[w * h];
        boolean[] yellow = new boolean[w * h];

        for (int y = 0; y < h; y += 2) {

            for (int x = 0; x < w; x += 2) {

                int c = b.getPixel(x, y);

                int[] hsv = rgbToHsv(
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                );

                boolean t =
                        isTeal(
                                hsv[0],
                                hsv[1],
                                hsv[2]
                        );

                boolean yel =
                        isYellow(
                                hsv[0],
                                hsv[1],
                                hsv[2]
                        );

                if (t || yel) {
                    platform[y * w + x] = true;
                }

                if (yel) {
                    yellow[y * w + x] = true;
                }

                /*
                 * Fill the skipped pixel in a 2x2 block.  This avoids holes
                 * caused by the sampling stride.
                 */
                if (x + 1 < w) {
                    platform[y * w + x + 1] =
                            platform[y * w + x];

                    yellow[y * w + x + 1] =
                            yellow[y * w + x];
                }

                if (y + 1 < h) {
                    platform[(y + 1) * w + x] =
                            platform[y * w + x];

                    yellow[(y + 1) * w + x] =
                            yellow[y * w + x];

                    if (x + 1 < w) {
                        platform[(y + 1) * w + x + 1] =
                                platform[y * w + x];

                        yellow[(y + 1) * w + x + 1] =
                                yellow[y * w + x];
                    }
                }
            }
        }

        return new boolean[][]{
                platform,
                yellow
        };
    }

    private boolean isTeal(int hue, int sat, int val) {
        return hue >= 80 &&
                hue <= 115 &&
                sat >= 65 &&
                val >= 40;
    }

    private boolean isYellow(int hue, int sat, int val) {
        return hue >= 18 &&
                hue <= 50 &&
                sat >= 105 &&
                val >= 110;
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

        if (hue < 0) hue += 360f;

        float saturation =
                max == 0 ? 0 : delta / max;

        return new int[]{
                Math.round(hue / 2f),
                Math.round(saturation * 255f),
                Math.round(max * 255f)
        };
    }

    private void resetControlMemory() {
        pendingSwipeSign = 0;
        pendingBeforeScore = Float.NaN;
        pendingSwipeAt = 0;
        stableBoardFrames = 0;
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void updateNotification() {
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate < 700) return;
        lastNotificationUpdate = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(7, buildNotification());
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(
                this,
                "autoplayer"
        )
                .setContentTitle("Vluck Auto Player V4")
                .setContentText("Frames: " + frameCount + " | Actions: " + actionCount)
                .setSmallIcon(android.R.drawable.ic_media_play)
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

    private static class Interval {
        final int start;
        final int end;

        Interval(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class PlayerAndPlan {
        final int playerY;
        final int bestTargetX;
        final float dangerScore;

        PlayerAndPlan(
                int playerY,
                int bestTargetX,
                float dangerScore) {

            this.playerY = playerY;
            this.bestTargetX = bestTargetX;
            this.dangerScore = dangerScore;
        }
    }
}
