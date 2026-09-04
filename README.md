# StitchApp (Android Native)

Android app (Jetpack Compose) which runs native Kotlin stitcher on-device.

## Build locally
```bash
./gradlew :app:assembleDebug
```
## GitHub Actions
On push to `main`, a Debug APK artifact will be built and uploaded.

## Notes
- Uses SAF to copy INPUT to cache, runs native stitcher, then copies results to OUTPUT.
