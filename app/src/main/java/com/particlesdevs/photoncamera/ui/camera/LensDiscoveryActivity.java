package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LensDiscoveryActivity extends AppCompatActivity {
    @Inject SettingsManager settingsManager;
    private static final String TAG = "LensDiscovery";
    private ProgressBar progressBar;
    private TextView foundCountText;
    private LensAdapter adapter;
    private final List<LensInfo> foundLenses = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lens_discovery);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        progressBar = findViewById(R.id.progress_bar);
        foundCountText = findViewById(R.id.found_count);
        RecyclerView recyclerView = findViewById(R.id.lens_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LensAdapter(foundLenses);
        recyclerView.setAdapter(adapter);

        startDiscovery();
    }

    private void startDiscovery() {
        new Thread(() -> {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                // 1. Standard IDs
                String[] ids = manager.getCameraIdList();
                for (String id : ids) {
                    addLensInfo(manager, id, "Reported");
                }

                // 2. Brute force
                boolean isSamsung = Build.BRAND.equalsIgnoreCase("samsung") || Build.BRAND.equalsIgnoreCase("google");
                for (int i = 0; i < 150; i++) {
                    checkAndAdd(manager, String.valueOf(i), "Hidden");
                    if (isSamsung) {
                        checkAndAdd(manager, "0-" + i, "Hidden (SS)");
                    }
                    // Some vendors (Xiaomi, etc.) use slash or dash separated IDs for physical sub-cameras
                    for (int j = 0; j < 10; j++) {
                        checkAndAdd(manager, i + "/" + j, "Hidden (Alt)");
                        checkAndAdd(manager, i + "-" + j, "Hidden (Alt)");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Discovery failed", e);
            }

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void checkAndAdd(CameraManager manager, String id, String source) {
        synchronized (foundLenses) {
            for (LensInfo info : foundLenses) {
                if (info.id.equals(id)) return;
            }
        }
        addLensInfo(manager, id, source);
    }

    private void addLensInfo(CameraManager manager, String id, String source) {
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            final LensInfo info = new LensInfo();
            info.id = id;
            info.source = source;
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) {
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) info.facing = "Front";
                else if (facing == CameraCharacteristics.LENS_FACING_BACK) info.facing = "Back";
                else if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) info.facing = "External";
            } else {
                info.facing = "Unknown";
            }
            
            float[] focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focals != null && focals.length > 0) {
                info.focalLength = focals[0];
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Set<String> physicalIds = chars.getPhysicalCameraIds();
                if (physicalIds != null && !physicalIds.isEmpty()) {
                    info.isLogical = true;
                    info.physicalIds = new ArrayList<>(physicalIds);
                }
            }

            int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                for (int cap : capabilities) {
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                        info.supportsRaw = true;
                        break;
                    }
                }
            }

            runOnUiThread(() -> {
                synchronized (foundLenses) {
                    // Check for duplicates again to be sure
                    for (LensInfo existing : foundLenses) {
                        if (existing.id.equals(info.id)) return;
                    }
                    foundLenses.add(info);
                    adapter.notifyItemInserted(foundLenses.size() - 1);
                    foundCountText.setText(getString(R.string.lens_discovery_found_count, foundLenses.size()));
                }
            });

        } catch (Exception ignored) {
            // Lens not found or inaccessible
        }
    }

    private static class LensInfo {
        String id;
        String source;
        String facing;
        float focalLength;
        boolean isLogical;
        boolean supportsRaw;
        List<String> physicalIds = new ArrayList<>();
    }

    private class LensAdapter extends RecyclerView.Adapter<LensAdapter.ViewHolder> {
        private final List<LensInfo> lenses;

        LensAdapter(List<LensInfo> lenses) {
            this.lenses = lenses;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lens_info, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LensInfo info = lenses.get(position);
            holder.idText.setText("ID: " + info.id + " (" + info.source + ")");
            StringBuilder sb = new StringBuilder();
            sb.append("Facing: ").append(info.facing);
            if (info.focalLength > 0) {
                sb.append("\nFocal Length: ").append(info.focalLength).append("mm");
            }
            sb.append("\nType: ").append(info.isLogical ? "Logical" : "Physical");
            sb.append("\nSupports RAW: ").append(info.supportsRaw ? "Yes" : "No");
            holder.detailsText.setText(sb.toString());

            if (!info.physicalIds.isEmpty()) {
                holder.physicalIdsText.setVisibility(View.VISIBLE);
                holder.physicalIdsText.setText("Physical IDs: " + info.physicalIds.toString());
            } else {
                holder.physicalIdsText.setVisibility(View.GONE);
            }

            Set<String> hiddenIds = settingsManager.getStringSet(SettingsManager.SCOPE_GLOBAL, "hidden_camera_ids", new HashSet<>());
            Set<String> userIds = settingsManager.getStringSet(SettingsManager.SCOPE_GLOBAL, "user_camera_ids", new HashSet<>());

            boolean isUserAdded = userIds.contains(info.id);
            boolean isHidden = hiddenIds.contains(info.id);
            
            // A lens is "Active" if it's NOT explicitly hidden.
            // (Even if it wasn't user-added, if the system reported it and it's not hidden, it's active)
            // Wait, we need to know if it's currently in the CameraManager2's list.
            
            if (isHidden) {
                holder.enableBtn.setText("Show/Enable");
                holder.enableBtn.setOnClickListener(v -> {
                    Set<String> newHidden = new HashSet<>(hiddenIds);
                    newHidden.remove(info.id);
                    settingsManager.set(SettingsManager.SCOPE_GLOBAL, "hidden_camera_ids", newHidden);
                    
                    Set<String> newUser = new HashSet<>(userIds);
                    newUser.add(info.id);
                    settingsManager.set(SettingsManager.SCOPE_GLOBAL, "user_camera_ids", newUser);
                    
                    notifyItemChanged(position);
                    Toast.makeText(LensDiscoveryActivity.this, "Lens enabled. Restart app.", Toast.LENGTH_SHORT).show();
                });
            } else {
                holder.enableBtn.setText("Hide");
                holder.enableBtn.setOnClickListener(v -> {
                    Set<String> newHidden = new HashSet<>(hiddenIds);
                    newHidden.add(info.id);
                    settingsManager.set(SettingsManager.SCOPE_GLOBAL, "hidden_camera_ids", newHidden);
                    
                    notifyItemChanged(position);
                    Toast.makeText(LensDiscoveryActivity.this, "Lens hidden. Restart app.", Toast.LENGTH_SHORT).show();
                });
            }
        }

        @Override
        public int getItemCount() {
            return lenses.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView idText, detailsText, physicalIdsText;
            Button enableBtn;

            ViewHolder(View itemView) {
                super(itemView);
                idText = itemView.findViewById(R.id.lens_id);
                detailsText = itemView.findViewById(R.id.lens_details);
                physicalIdsText = itemView.findViewById(R.id.physical_ids);
                enableBtn = itemView.findViewById(R.id.btn_enable);
            }
        }
    }
}
