# MediaCinemaRAW

PhotonCamera's MediaCinemaRAW format writes timestamped `VID_*.mcraw` files directly
in the `Raw` directory, replacing DNG-in-ZIP. PCM audio and binary gyroscope samples
are embedded with their container indexes. The former Write ZIP switch is removed.

MediaCinemaRAW uses container version 3 and compression type 7. This is an
independent clean-room implementation compatible with the format read by
https://github.com/mirsadm/motioncam-decoder, verified against commit
`592574938324104210cc3f1eace84df5a4299eb8`. The separate decoder codebase is used
only as an external interoperability test oracle and is not copied into or bundled
with PhotonCamera.

RAW16 values retain their full precision. Android packed RAW10 is unpacked to
its original 10-bit values. Row padding is excluded from visible pixels. The
optional downscale averages four same-colour Bayer samples (half width/height),
retaining black/white levels; that optional averaging is not lossless. Crop
origins stay even to preserve CFA phase. Heights are trimmed to multiples of
four output rows because the supplied decoder emits four rows per iteration.
Frame timestamps are the camera's nanosecond timestamps. Capture metadata is
matched by timestamp; on a missing result, the initial capture metadata is used
and `metadataMatched` is false with the source `metadataTimestamp` recorded.

Each recording owns two encoder workers, a separate ordered writer, its file,
audio recorder, and a bounded pool of encoded buffers. Camera images pass directly
to the encoder and close as soon as encoding completes. Stop rejects new frames,
drains both stages, then writes the index and closes without blocking the UI. Audio start/stop is
serialized separately; native FLAC resources are never released before its
recording thread exits. Gyro stop and sensor callbacks share a lock so stop
cannot clear arrays while a callback is writing them.

The final index is flushed and synced on orderly stop. Failed frame writes are
truncated back to the previous complete frame before finalization is attempted.
A killed process, power loss, or storage failure preventing the final index write
can still leave an incomplete file; this is not a crash-recovery journal. Existing
corrupted ZIP recordings are not repaired by this change.

## Build and install

Debug uses `com.particlesdevs.photoncamera.test`, labelled **PhotonCamera Test**.
Release keeps `com.particlesdevs.photoncamera`. Test permissions and settings are
separate; grant camera/microphone/storage access when first launching it.

```sh
/bin/sh gradlew :app:assembleDebug
```

## Verification

Host codec and container interoperability, using an existing decoder checkout:

```sh
python3 tools/mcraw/verify.py /path/to/motioncam-decoder
```

This checks 240 deterministic cases against the upstream decoder and verifies a
Java-written two-frame container. AddressSanitizer and UndefinedBehaviorSanitizer
cover our encoder; alignment checking is disabled only for upstream decoder
objects because its bundled SIMDe uses unaligned vector pointer casts.

```sh
/bin/sh gradlew :app:testDebugUnitTest --tests 'com.particlesdevs.photoncamera.processing.mcraw.*'
/bin/sh gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.particlesdevs.photoncamera.processing.mcraw.McrawEncoderTest
```

The device tests exercise native JNI RAW16/RAW10 encoding, invalid input and five
write/stop cycles. They leave `mcraw-test.mcraw` in the test app's external files
directory. Passing that file as the second argument to `verify.py` additionally
checks its decoded pixels with the upstream decoder. These synthetic tests do
not measure sustained camera recording throughput or thermal limits.
