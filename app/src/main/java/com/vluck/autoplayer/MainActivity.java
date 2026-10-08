package com.vluck.autoplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        Button accessibility = findViewById(R.id.accessibility);
        Button startCapture = findViewById(R.id.startCapture);
        Button stop = findViewById(R.id.stop);

        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        startCapture.setOnClickListener(v -> requestCapture());

        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            status.setText("Status: STOPPED");
        });
    }

    private void requestCapture() {
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(CaptureService.EXTRA_DATA, data);
            startForegroundService(service);
            status.setText("Status: RUNNING");
        }
    }
}
