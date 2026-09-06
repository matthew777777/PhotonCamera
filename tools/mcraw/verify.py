#!/usr/bin/env python3
"""Verify against an existing checkout of https://github.com/mirsadm/motioncam-decoder."""
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parents[2]
decoder = pathlib.Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix="photon-mcraw-") as tmp:
    build = pathlib.Path(tmp)
    flags = ["clang++", "-std=c++17", "-O2", "-fsanitize=address,undefined",
             "-fno-omit-frame-pointer", "-I" + str(root / "app/src/main/cpp/mcraw"),
             "-I" + str(decoder / "lib/include"), "-I" + str(decoder / "thirdparty")]
    objects = []
    for source in [root / "app/src/main/cpp/mcraw/Encoder.cpp", root / "tools/mcraw/roundtrip.cpp",
                   decoder / "lib/Decoder.cpp", decoder / "lib/RawData.cpp", decoder / "lib/RawData_Legacy.cpp"]:
        obj = build / (source.stem + ".o")
        # Upstream SIMDe intentionally performs unaligned loads via vector pointer casts.
        extra = ["-fno-sanitize=alignment"] if decoder in source.parents else []
        subprocess.run(flags + extra + ["-c", str(source), "-o", str(obj)], check=True)
        objects.append(str(obj))
    exe = build / "roundtrip"
    subprocess.run(flags + objects + ["-o", str(exe)], check=True)
    subprocess.run([str(exe), "--fixture", str(build / "frame.bin")], check=True)
    subprocess.run(["javac", "-d", str(build),
                    str(root / "app/src/main/java/com/particlesdevs/photoncamera/processing/mcraw/McrawWriter.java"),
                    str(root / "tools/mcraw/ContainerFixture.java")], check=True)
    subprocess.run(["java", "-cp", str(build), "ContainerFixture", str(build / "frame.bin"),
                    str(build / "fixture.mcraw")], check=True)
    subprocess.run([str(exe), str(build / "fixture.mcraw")], check=True)
    if len(sys.argv) > 2:
        subprocess.run([str(exe), str(pathlib.Path(sys.argv[2]).resolve())], check=True)
