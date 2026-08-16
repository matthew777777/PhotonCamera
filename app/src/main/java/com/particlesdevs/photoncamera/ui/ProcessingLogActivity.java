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
import com.particlesdevs.photoncamera.databinding.ActivityProcessingLogBinding;
import com.particlesdevs.photoncamera.processing.ProcessingLog;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ProcessingLogActivity extends BaseActivity {
    private ActivityProcessingLogBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(PreferenceKeys.getThemeValue());
        super.onCreate(savedInstanceState);
        binding = ActivityProcessingLogBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        ProcessingLog log = PhotonCamera.getLatestProcessingLog();
        if (log != null) {
            binding.logText.setText("Loading log details...");
            executor.execute(() -> {
                String content = log.toString() + "\n" + Log.getRelevantLogsSince(log.startTime);
                runOnUiThread(() -> binding.logText.setText(content));
            });
        } else {
            binding.logText.setText("No log data available. Capture a photo first.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
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

        String content = log.toString() + "\n" + Log.getRelevantLogsSince(log.startTime);

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "PhotonCamera Processing Log");
        intent.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(intent, "Share Log via"));
    }
}
