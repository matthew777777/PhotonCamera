package com.particlesdevs.photoncamera.processing.parameters;

import com.particlesdevs.photoncamera.util.Log;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class IsoExpoSelectorTest {

    @Before
    public void setUp() {
        Log.setLogEnabled(false);
    }

    @Test
    public void testApplyShutterPriorityCurve_BLE() {
        // Mocking an ExpoPair
        // public ExpoPair(long expo, long expl, long exph, int is, int islow, int ishigh, int analog)
        long sec = ExposureIndex.sec;
        IsoExpoSelector.ExpoPair pair = new IsoExpoSelector.ExpoPair(
                sec / 100, // 10ms exposure
                sec / 10000,
                sec,
                100, // ISO 100
                100, // isolow
                3200, // isohigh
                400 // isoanalog (HCG point)
        );

        // Scenario: Dark scene, metered energy requires ISO 1600 at 1/30s
        // totalEnergy = (1/30) * 1600 = 53.33
        // energyAtCapStart = (1/30) * 100 = 3.33
        // stopsPastStart = log2(53.33 / 3.33) = 4.0
        // dynamicCap = 1/15s
        pair.exposure = sec / 30;
        pair.iso = 1600;

        // Apply curve with capStart=1/30s, capEnd=1/15s
        pair.applyShutterPriorityCurve(sec / 30, sec / 15, 4.0);

        // We expect it to extend exposure to 1/15s and keep ISO at 800 (or snap to HCG 400 if it was close)
        // At 1/15s (0.066), ISO needed = 53.33 / 0.066 = 800.
        
        System.out.println("Result Expo: " + ExposureIndex.sec2string(ExposureIndex.time2sec(pair.exposure)));
        System.out.println("Result ISO: " + pair.iso);

        assertEquals(800, pair.iso);
        assertEquals(sec / 15, pair.exposure);
    }

    @Test
    public void testDCG_Awareness() {
        long sec = ExposureIndex.sec;
        IsoExpoSelector.ExpoPair pair = new IsoExpoSelector.ExpoPair(
                sec / 30, 
                sec / 10000,
                sec,
                300, // ISO 300 needed
                100, 
                3200, 
                400 // HCG at 400
        );
        
        // Scenario: Metered energy is (1/30) * 300 = 10.
        // Shutter cap is 1/30s. isoMinToFit = 300.
        // HCG jump point is 0.7 * 400 = 280.
        // Since 300 > 280, it should jump to 400 (HCG) for better SNR.
        
        pair.applyShutterPriorityCurve(sec / 30, sec / 30, 4.0);
        
        System.out.println("DCG Result ISO: " + pair.iso);
        assertEquals(400, pair.iso);
        // Exposure should be reduced to compensate
        // (1/30) * 300 / 400 = 1/40s = 25,000,000 ns
        assertTrue(Math.abs(sec / 40 - pair.exposure) <= 1);
    }
}
