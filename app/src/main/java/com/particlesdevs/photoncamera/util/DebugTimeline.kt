package com.particlesdevs.photoncamera.util

import android.os.SystemClock
import android.util.Log

object DebugTimeline {
    private const val TAG = "DebugTimeline"
    
    fun log(event: String) {
        val timestamp = SystemClock.elapsedRealtimeNanos() / 1_000_000.0 // ms
        val threadName = Thread.currentThread().name
        Log.d(TAG, String.format("[%s] %.3f ms: %s", threadName, timestamp, event))
    }
}
