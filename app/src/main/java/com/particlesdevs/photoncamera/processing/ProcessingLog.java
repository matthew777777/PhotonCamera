package com.particlesdevs.photoncamera.processing;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProcessingLog {
    public int totalFrames = 0;
    public int mergedFrames = 0;
    public int discardedFrames = 0;
    
    public static class FrameInfo {
        public int index;
        public int ev;
        public String status;
        public float sharpness;
        public String reason = "";

        public FrameInfo(int index, int ev, String status, float sharpness) {
            this.index = index;
            this.ev = ev;
            this.status = status;
            this.sharpness = sharpness;
        }
    }
    
    public List<FrameInfo> frameInfos = new ArrayList<>();
    
    public Map<String, String> jpgSettings = new LinkedHashMap<>();
    
    public long mergeTimeMs = 0;
    public long jpgTimeMs = 0;
    public long totalTimeMs = 0;

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ MERGE ENGINE ]\n\n");
        sb.append("Frames merged: ").append(mergedFrames).append("/").append(totalFrames).append("\n");
        sb.append("Frames discarded: ").append(discardedFrames).append("\n\n");
        
        for (FrameInfo info : frameInfos) {
            sb.append("F").append(info.index).append(" EV=").append(info.ev > 0 ? "+" : "").append(info.ev);
            sb.append(": ").append(info.status);
            if (!info.reason.isEmpty()) {
                sb.append(" (").append(info.reason).append(")");
            }
            sb.append(" ? sharpness=").append(String.format("%.2f", info.sharpness)).append("\n");
        }
        
        sb.append("mergeCore=").append(mergeTimeMs).append("ms\n");
        
        sb.append("\n[ JPG ENGINE ]\n\n");
        sb.append("Settings:\n");
        int count = 0;
        for (Map.Entry<String, String> entry : jpgSettings.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            if (++count < jpgSettings.size()) {
                sb.append(";");
            }
            if (count % 3 == 0) sb.append("\n");
        }
        
        sb.append("\n\nTotal processing time: ").append(totalTimeMs).append("ms");
        
        return sb.toString();
    }
}
