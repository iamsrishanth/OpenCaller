# OpenCaller

OpenCaller is an open-source Android phone app for everyday SIM calling, with an optional self-hosted internet calling line. It is written in Kotlin and Jetpack Compose, and is licensed under GPL-3.0-or-later.

## Current implementation status

- **SIM path:** an accessible T9-style dial pad, `ACTION_DIAL`, a user-triggered default-phone request, SIM call placement through Android Telecom, and an incoming/ongoing `InCallService` screen with capability-gated controls.
- **Updates:** Settings → About → Updates checks GitHub Releases and the official F-Droid package API independently. It compares numeric Android version codes, preserves each source's last successful result, and keeps optional daily checks off until enabled. Release tags must use `vMAJOR.MINOR.PATCH`; the release workflow builds the APK metadata and attaches `opencaller-update.json`.
- **Self-hosted server:** `dev/asterisk/` contains a pinned Asterisk lab with two authenticated test extensions and a local voicemail fallback. It is not yet connected to an Android SIP client or the in-app inbox.

The current app does not yet implement an Android SIP line, two-sided recording, live voicemail, server inbox/sync, caller rules, SIM carrier voicemail, contacts, recents, or speech transcription. Do not treat the Asterisk lab as evidence that these app features exist.

### Verification snapshot

Checked on **2026-11-22**:

- Moto G57 Power, Android 17 / API 37: debug APK installed and launched; the update screen reached both live services and showed “No stable release yet” for GitHub and “Not listed yet” for F-Droid. Google Dialer remained the default phone app; no calls were placed.
- Unit tests cover voicemail handoff races and update-source fixtures. `testDebugUnitTest`, `assembleDebug`, `lintDebug`, and `assembleRelease` passed. The release workflow's tag calculation was checked with `v0.1.0`; it produces version name `0.1.0` and version code `1001`.
- The Asterisk lab has not yet been run. The available Linux hosts are being checked for a safe isolated setup.

No GitHub stable release or F-Droid listing exists yet, so no APK download or F-Droid install is available. The release APK built locally is unsigned and is not suitable for publishing.

## Build

Install Android Studio or the Android command-line tools, an Android SDK platform for API 37, and a JDK supported by the Gradle wrapper. Then run:

```powershell
./gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To run the unit tests and lint check:

```powershell
./gradlew.bat testDebugUnitTest lintDebug
```

The stable release workflow derives Android version values from the release tag. For a local release build, pass the corresponding `opencallerVersionName` and `opencallerVersionCode` Gradle properties.

## License

OpenCaller is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).
