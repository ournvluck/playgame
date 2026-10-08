package com.vluck.autoplayer;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import java.util.*;

public class CaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;
    private long lastAction = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(7, buildNotification());
        thread = new HandlerThread("screen-analysis");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        if (data != null && projection == null) {
            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, data);

            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            int density = dm.densityDpi;

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> analyze(reader), handler);

            virtualDisplay = projection.createVirtualDisplay(
                    "VluckAutoPlayer",
                    w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler);
        }
        return START_STICKY;
    }

    private void analyze(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane plane = image.getPlanes()[0];
            int width = image.getWidth();
            int height = image.getHeight();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap full = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(plane.getBuffer());

            // Analyze the central game area. Ignore status/navigation bars.
            int left = (int)(full.getWidth() * 0.05);
            int right = (int)(full.getWidth() * 0.95);
            int top = (int)(full.getHeight() * 0.10);
            int bottom = (int)(full.getHeight() * 0.92);

            // Find yellow pixels by HSV-like RGB threshold.
            // This is deliberately conservative for the bright yellow hazard.
            int[] yellow = new int[full.getWidth()];
            int scanY = top + (int)((bottom - top) * 0.58);

            for (int x = left; x < right; x++) {
                int c = full.getPixel(x, scanY);
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);
                boolean isYellow = r > 190 && g > 170 && b < 100 && r > b * 1.8;
                yellow[x] = isYellow ? 1 : 0;
            }

            // Convert yellow runs into intervals.
            ArrayList<int[]> danger = new ArrayList<>();
            int runStart = -1;
            for (int x = left; x <= right; x++) {
                boolean y = x < right && yellow[x] == 1;
                if (y && runStart < 0) runStart = x;
                if ((!y || x == right) && runStart >= 0) {
                    if (x - runStart >= Math.max(8, full.getWidth() / 100)) {
                        danger.add(new int[]{runStart, x - 1});
                    }
                    runStart = -1;
                }
            }

            // Find the largest safe interval between danger runs.
            ArrayList<int[]> safe = new ArrayList<>();
            int cursor = left;
            for (int[] d : danger) {
                if (d[0] - cursor > full.getWidth() * 0.08) {
                    safe.add(new int[]{cursor, d[0]});
                }
                cursor = Math.max(cursor, d[1] + 1);
            }
            if (right - cursor > full.getWidth() * 0.08) {
                safe.add(new int[]{cursor, right});
            }

            if (!safe.isEmpty() && System.currentTimeMillis() - lastAction > 180) {
                int[] best = safe.get(0);
                for (int[] s : safe) {
                    if ((s[1] - s[0]) > (best[1] - best[0])) best = s;
                }

                int targetX = (best[0] + best[1]) / 2;
                int centerX = full.getWidth() / 2;

                // Conservative steering. We only move when target is meaningfully
                // left/right of the current center.
                int deadZone = (int)(full.getWidth() * 0.06);
                if (Math.abs(targetX - centerX) > deadZone &&
                        GameAccessibilityService.isReady()) {

                    int delta = (int)(full.getWidth() * 0.18);
                    int endX = targetX < centerX
                            ? Math.max(30, centerX - delta)
                            : Math.min(full.getWidth() - 30, centerX + delta);

                    int y = (int)(full.getHeight() * 0.62);
                    GameAccessibilityService.swipe(
                            centerX, y, endX, y, 120);
                    lastAction = System.currentTimeMillis();
                }
            }

            full.recycle();
        } catch (Throwable ignored) {
            // Keep the game loop alive if a frame is unavailable.
        } finally {
            if (image != null) image.close();
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, "autoplayer")
                .setContentTitle("Vluck Auto Player")
                .setContentText("Screen analysis is running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    "autoplayer", "Auto Player",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
