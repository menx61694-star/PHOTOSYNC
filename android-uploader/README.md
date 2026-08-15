# PHOTOSYNC Android uploader

## Build

Open `android-uploader` as an Android Studio project and let Gradle sync. Build the debug APK with:

```bash
./gradlew :app:assembleDebug
```

The generated APK is under `app/build/outputs/apk/debug/`.

## Server URL

`MainActivity.kt` currently uses `http://10.0.2.2:8000`, which is the Android Emulator alias for the host machine. For a physical phone, replace it with the reachable IP address of the machine running PHOTOSYNC, for example `http://192.168.1.10:8000`.

The current prototype is intended for local testing. Do not expose it publicly until authentication and HTTPS are implemented.
