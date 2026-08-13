# Implementation Plan - Update Processing Log Tab

This plan details the updates to the "Processing Log" tab to show more detailed JPEG pipeline information and to merge the structured processing log with the system logs saved in `DCIM/PhotonCamera/PhotonLog`.

## User Review Required

> [!IMPORTANT]
> The merging of system logs with the processing log will significantly increase the amount of text displayed in the Processing Log tab. The layout already uses a `ScrollView`, so it should handle this, but performance might be an issue if the log file is very large. I will implement a reading mechanism that retrieves the current day's log.

## Proposed Changes

### [Component] Processing Log Data Model & Population

I will enhance the `ProcessingLog` with more detailed JPEG pipeline information.

#### [MODIFY] [PostPipeline.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/PostPipeline.java)
- Update `Run(ByteBuffer, Parameters, ProcessingLog)` to include:
    - `pipeline_active`: "Experimental" or "Legacy".
    - `demosaic_method`: Value of `demosaicingMethod` (if Legacy).
    - `experimental_demosaic`: Value of `experimentalDemosaic` (if Experimental).
- Add other relevant parameters like `noise_s`, `noise_o` to `jpgSettings` for better debugging.

#### [MODIFY] [HdrxProcessor.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/processing/processor/HdrxProcessor.java)
- In `ApplyHdrX`, before calling `PhotonCamera.setLatestProcessingLog(processingLog)`, add JPEG quality and chroma subsampling information to `processingLog.jpgSettings`.

---

### [Component] Logging Utilities

I will add a method to read the log file from storage.

#### [MODIFY] [Log.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/util/Log.java)
- Add `public static String getLogFileContent()`:
    - It will locate the current day's log file using `getLogFileDocumentFile()`.
    - It will read the file content using `DocumentFileUtils.openInputStream`.
    - It will return the content as a String, or an error message if it cannot be read.

---

### [Component] UI

I will update the Processing Log activity to display the merged data.

#### [MODIFY] [ProcessingLogActivity.java](file:///Users/monikamalinowska/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/ui/ProcessingLogActivity.java)
- Update `onCreate` to:
    - Get the `latestProcessingLog` text.
    - Call `Log.getLogFileContent()`.
    - Combine them: Structured Processing Log first, followed by a separator and the System Logs.
    - Update `shareLog()` to also share the merged data.

## Verification Plan

### Manual Verification
1.  **Check JPEG Pipeline Data**:
    - Open Settings and toggle "Experimental JPEG Pipeline".
    - Capture a photo.
    - Open the Processing Log tab and verify that the correct pipeline info (Experimental vs Legacy) and demosaic settings are shown.
2.  **Verify Log Merging**:
    - Ensure that the app has granted storage permissions.
    - Capture a photo.
    - Open the Processing Log tab and verify that the system logs from `DCIM/PhotonCamera/PhotonLog` are appended after the capture-specific data.
    - Verify that sharing the log includes both parts.
