# Capture and measurement notes — 2026-10-15

Device: onn. 4K Streaming Box (YOC), Android 14. Physical display size reported by Android: 3840 × 2160; configured UI override: 1920 × 1080. Screenshots are direct 1920 × 1080 ADB captures. The Nuvio Legacy GIFs were recorded at 1280 × 720 and exported at 960 × 540 (opening: 20 fps; return and Recents: 25 fps). The opening demo reaches Nuvio's loaded home screen; the return animation is a separate recording. GIF frame rate is an export setting, not a performance measurement.

## Provenance

Source base: `3cc8e52`, plus pre-existing local edits to `HeroView.java`, `HomeActivity.java`, and `ImageLoader.java`. Those edits are deliberately outside this documentation commit. `./build.sh` passed; it emitted only a deprecated-API notice.

Local APK: 62,295 bytes; SHA-256 `e81188febeede2d0d6b5b5d2fe25d822f94d2a0a7f20db1c438b95cc3d3e792c`.

Installed APK: 62,295 bytes; SHA-256 `2e4e44c77c018a807a4032406cb8d57ade6b020091886c2023bb79c27b8af8a4`.

All local APK ZIP entries outside `META-INF/` were compared byte-for-byte with the installed APK and matched, including executable code and resources. Whole-file hashes differ; the installed artifact was not replaced during this session.

## Method and results

The launcher was already running. After visiting the home screen and tile-style menu, memory was read with `adb shell dumpsys meminfo com.sghmire.flow`. [Raw memory snapshot](memory.txt): total PSS 121,702 KiB (118.8 MiB); RSS 219,164 KiB (214.0 MiB). This includes the current artwork/cache state and is not a startup or steady-state average.

Frame counters were reset with `adb shell dumpsys gfxinfo com.sghmire.flow reset`. From the YouTube dock tile, four Left presses and four Right presses were sent, with 0.6 seconds after each press. [Raw frame statistics](dock-frames.txt): 307 rendered frames, 8 janky frames (2.61%), p50 12 ms, p90 18 ms, p95 20 ms, p99 36 ms. These are Android gfxinfo summary values, not independently measured input latency. Screen recording was performed afterward, outside this sample.

No repeated trials, cold-start timings, battery measurements, before/after comparison, or exhaustive device testing were performed. The captures verify visible home, app-menu, tile-style, recent-app, and launch/return flows on this device; they do not validate every feature.

## Current Nuvio captures

README screenshots and all three GIFs use Nuvio Legacy on the same TV on 2026-10-15. `opening.gif` follows the dock launch through the splash screen to the loaded home screen, also saved as `app-landing.png`. `app-return.gif` shows Home returning to Flow. `recents-dismiss.gif` shows Recents opening and Nuvio being dismissed. Other installed apps remain visible in the dock. These are visual demonstrations, not new performance samples; the earlier measurement conditions remain unchanged.

## Background refresh

Removed the oval light pool above the dock, softened the edge vignette, and introduced a subtle blue-charcoal gradient in `BackdropView.java`. Built successfully and installed on the TV; all README captures were refreshed. Current APK remains 62,295 bytes, SHA-256 `98e57d1fb656be4eaf5940b60af4cdf0aa1f1f938404790efae4bc4b2d5adc0e`. The older numerical measurements and APK hashes above describe the pre-refresh build.

Nuvio replaced the previous Netflix demos because its loaded home screen is visible in ADB captures. No source changes or new performance measurements were made for this capture replacement. Existing unrelated source edits remain uncommitted.

The package was subsequently renamed to `com.gfs.flow`. Raw measurements and commands above retain the original `com.sghmire.flow` identity used during capture.
