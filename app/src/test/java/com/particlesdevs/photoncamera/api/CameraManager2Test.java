package com.particlesdevs.photoncamera.api;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Size;

import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class CameraManager2Test {

    private CameraManager2 cameraManager2;
    private SettingsManager settingsManager;
    private android.hardware.camera2.CameraManager cameraManager;

    @Before
    public void setUp() {
        settingsManager = Mockito.mock(SettingsManager.class);
        cameraManager = Mockito.mock(android.hardware.camera2.CameraManager.class);
        // We avoid calling the constructor because it has complex logic and spinlocks
    }

    @Test
    public void testFindLensZoomFactor_StandardIds() {
        Map<String, CameraLensData> dataMap = new HashMap<>();
        
        // Main back (ID 0)
        CameraLensData back0 = createLens("0", CameraCharacteristics.LENS_FACING_BACK, 4.38f, 26f);
        dataMap.put("0", back0);
        
        // Wide back (ID 2)
        CameraLensData back2 = createLens("2", CameraCharacteristics.LENS_FACING_BACK, 2.2f, 13f);
        dataMap.put("2", back2);
        
        // Tele back (ID 3)
        CameraLensData back3 = createLens("3", CameraCharacteristics.LENS_FACING_BACK, 7.5f, 52f);
        dataMap.put("3", back3);
        
        // Main front (ID 1)
        CameraLensData front1 = createLens("1", CameraCharacteristics.LENS_FACING_FRONT, 3.5f, 25f);
        dataMap.put("1", front1);

        // We can't easily instantiate CameraManager2 due to constructor logic, 
        // so we use a mock and call the package-private method if possible, 
        // or just test the logic if we move it to a static helper.
        // For now, let's assume we can use a "dummy" instance or mock.
        cameraManager2 = Mockito.mock(CameraManager2.class, Mockito.CALLS_REAL_METHODS);
        
        cameraManager2.findLensZoomFactor(dataMap);

        assertEquals(1.0f, back0.getZoomFactor(), 0.01f);
        assertEquals(0.5f, back2.getZoomFactor(), 0.01f); // 13/26
        assertEquals(2.0f, back3.getZoomFactor(), 0.01f); // 52/26
        assertEquals(1.0f, front1.getZoomFactor(), 0.01f);
    }

    @Test
    public void testFindLensZoomFactor_Heuristics() {
        Map<String, CameraLensData> dataMap = new HashMap<>();
        
        // No ID 0. Main back is ID 10
        CameraLensData back10 = createLens("10", CameraCharacteristics.LENS_FACING_BACK, 4.38f, 26f);
        dataMap.put("10", back10);
        
        // Tele back ID 11
        CameraLensData back11 = createLens("11", CameraCharacteristics.LENS_FACING_BACK, 9.0f, 78f);
        dataMap.put("11", back11);
        
        cameraManager2 = Mockito.mock(CameraManager2.class, Mockito.CALLS_REAL_METHODS);
        cameraManager2.findLensZoomFactor(dataMap);

        assertEquals(1.0f, back10.getZoomFactor(), 0.01f);
        assertEquals(3.0f, back11.getZoomFactor(), 0.01f); // 78/26
    }

    private CameraLensData createLens(String id, int facing, float focalLength, float focalLength35mm) {
        CameraLensData lens = new CameraLensData(id);
        lens.setFacing(facing);
        lens.setCameraFocalLength(focalLength);
        lens.setCamera35mmFocalLength(focalLength35mm);
        return lens;
    }
}
