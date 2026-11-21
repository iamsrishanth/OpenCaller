# OpenCaller

OpenCaller is an open-source Android phone app for everyday SIM calling, with an optional self-hosted internet calling line. It is written in Kotlin and Jetpack Compose, and is licensed under GPL-3.0-or-later.

The project is at its initial build milestone. The app currently installs and launches; SIM dialer features, the optional SIP line, recording, and voicemail are being built in follow-on milestones.

## Build

Install Android Studio or the Android command-line tools, an Android SDK platform for API 37, and a JDK supported by the Gradle wrapper. Then run:

```powershell
./gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## License

OpenCaller is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).
