package com.particlesdevs.photoncamera.di

import android.content.Context
import android.hardware.SensorManager
import android.media.AudioManager
import com.particlesdevs.photoncamera.api.Settings
import com.particlesdevs.photoncamera.control.Gravity
import com.particlesdevs.photoncamera.control.Gyro
import com.particlesdevs.photoncamera.control.Vibration
import com.particlesdevs.photoncamera.debugclient.Debugger
import com.particlesdevs.photoncamera.pro.SupportedDevice
import com.particlesdevs.photoncamera.processing.render.PreviewParameters
import com.particlesdevs.photoncamera.settings.SettingsManager
import com.particlesdevs.photoncamera.util.AssetLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsManager(@ApplicationContext context: Context): SettingsManager {
        return SettingsManager(context)
    }

    @Provides
    @Singleton
    fun provideSensorManager(@ApplicationContext context: Context): SensorManager {
        return context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Provides
    @Singleton
    fun provideGravity(sensorManager: SensorManager): Gravity {
        return Gravity(sensorManager)
    }

    @Provides
    @Singleton
    fun provideGyro(sensorManager: SensorManager): Gyro {
        return Gyro(sensorManager)
    }

    @Provides
    @Singleton
    fun provideVibration(@ApplicationContext context: Context): Vibration {
        return Vibration(context)
    }

    @Provides
    @Singleton
    fun provideSupportedDevice(settingsManager: SettingsManager, @ApplicationContext context: Context): SupportedDevice {
        return SupportedDevice(settingsManager, context)
    }

    @Provides
    @Singleton
    fun provideSettings(): Settings {
        return Settings()
    }

    @Provides
    @Singleton
    fun providePreviewParameters(): PreviewParameters {
        return PreviewParameters()
    }

    @Provides
    @Singleton
    fun provideAssetLoader(@ApplicationContext context: Context): AssetLoader {
        return AssetLoader(context)
    }

    @Provides
    @Singleton
    fun provideDebugger(): Debugger {
        return Debugger()
    }

    @Provides
    @Singleton
    fun provideExecutorService(): ExecutorService {
        return Executors.newSingleThreadExecutor { r ->
            Thread(r).apply {
                priority = Thread.MIN_PRIORITY
            }
        }
    }
}
