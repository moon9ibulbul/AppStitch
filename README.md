# StitchApp (Android + Chaquopy)

Android app (Jetpack Compose) which runs your Python stitcher (`main.py` + `SmartStitchCore.py`) on-device.

## Build locally
```bash
./gradlew :app:assembleDebug
```
## GitHub Actions
On push to `main`, a Debug APK artifact will be built and uploaded.

## Notes
- Uses SAF to copy INPUT to cache, runs Python, then copies results to OUTPUT.
- Python deps via Chaquopy pip: numpy, pillow, natsort.
- Your scripts are unmodified; glue is in `bridge.py`.