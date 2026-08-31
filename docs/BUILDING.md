# Building Pocket Toolkit

Pocket Toolkit is a native Android/Kotlin project.

## GitHub Actions

The `Android build` workflow runs unit tests and builds a debug APK on pushes, pull requests, and manual dispatches. Successful runs upload the APK as the `pocket-toolkit-debug` artifact.

## Local build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.7

Run:

```bash
gradle testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
