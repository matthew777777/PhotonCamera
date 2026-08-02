package com.particlesdevs.photoncamera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.app.base.BaseActivity;
import com.particlesdevs.photoncamera.processing.ProcessingLog;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

public class ProcessingLogActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(PreferenceKeys.getThemeValue());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processing_log);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView logText = findViewById(R.id.log_text);
        ProcessingLog log = PhotonCamera.getLatestProcessingLog();
        if (log != null) {
            logText.setText(log.toString());
        } else {
            logText.setText("No log data available. Capture a photo first.");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_processing_log, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_share) {
            shareLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void shareLog() {
        ProcessingLog log = PhotonCamera.getLatestProcessingLog();
        if (log == null) return;

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "PhotonCamera Processing Log");
        intent.putExtra(Intent.EXTRA_TEXT, log.toString());
        startActivity(Intent.createChooser(intent, "Share Log via"));
    }
}
