# OpenCaller

OpenCaller is an open-source Android phone app for everyday SIM calling, with an optional self-hosted internet calling line. It is written in Kotlin and Jetpack Compose, and is licensed under GPL-3.0-or-later.

## Current implementation status

- **SIM path:** an accessible T9-style dial pad, `ACTION_DIAL`, a user-triggered default-phone request, SIM call placement through Android Telecom, and an incoming/ongoing `InCallService` screen with capability-gated controls.
- **Self-hosted internet line:** the optional SIP line uses the pinned PJSIP/Mbed TLS native stack, verified TLS signaling, and SRTP media. SIP credentials and the server voicemail token are encrypted with Android Keystore.
- **Live voicemail:** incoming internet calls can be sent to a private greeting and tone; only caller media is recorded while the call microphone stays disconnected. The recipient can answer the same call after the recording is finalized. Auto handling supports Off, Selected callers, and All callers with a ring delay. Live transcription is optional and off by default; Android speech-provider support for caller-audio input must be verified on each device because an unsupported provider may open the handset microphone.
- **Voicemail inbox:** messages are private app files and include unread state, playback, seeking, transcript, callback, deletion, and a recent-call link. A separate authenticated HTTPS reader syncs the configured Asterisk mailbox after reconnecting. The offline fallback plays the Asterisk mailbox greeting; OpenCaller does not yet copy its locally recorded greeting to the server.
- **Updates:** Settings → About → Updates checks GitHub Releases and the official F-Droid package API independently. It compares numeric Android version codes, preserves each source's last successful result, and keeps optional daily checks off until enabled. Release tags use `vMAJOR.MINOR.PATCH`; the release workflow attaches `opencaller-update.json` with the built APK's application ID, version code, and version name.
- **SIM carrier voicemail:** OpenCaller does not expose carrier-specific voicemail controls because no carrier capability has been verified. The voicemail comparison is in [the research note](docs/research/live-voicemail-comparison.md).

### Verification snapshot

Checked on **2026-11-22**:

- The pinned Android native library was rebuilt on Zeus from PJSIP 2.17 and Mbed TLS 3.6.8; the arm64 library's LOAD segments are 16 KB aligned.
- The release-candidate task set (`:app:testDebugUnitTest --rerun-tasks`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:assembleRelease`) passed for version 0.1.5 (versionCode 1006), with 47 unit tests at that point. After transcript-retention fixes, `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` passed again; all 52 unit tests passed, including live handoff races, sync retry/deduplication/tombstones, transcript retention, and update-source fixtures. The release APK is unsigned. The three Python HTTPS mailbox tests passed.
- Moto G57 Power, Android 17 / API 37: version 0.1.5 installed over 0.1.4 without uninstalling; Android kept the original first-install time. A synthetic voicemail saved under 0.1.4 remained in private storage, decoded and started in `MediaPlayer` after the upgrade, then was removed. The PJSIP native endpoint smoke test also passed on the Moto.
- x86_64 emulator builds passed on API 26, 34, and 37: each installed version 0.1.5, started/stopped the PJSIP native endpoint, and seeded, decoded/played, and removed a synthetic voicemail fixture. The API 37 emulator also opened **Settings → About → Updates** and showed the current GitHub and F-Droid results independently. The ordinary build and release artifact remain arm64 by default; `-PopencallerNativeAbi=x86_64` selects the emulator ABI.
- A test-only version 0.1.6-test / versionCode 1007 build of the current source was installed over 0.1.5 / 1006 on the API 37 emulator without uninstalling. Android kept the original first-install time; all 12 voicemail WAV hashes matched before and after, and the 36-second saved message played through 0:02 after the update. The repository's app version remains 0.1.5 / 1006.
- On the API 37 x86_64 emulator, a controlled Baresip call to app extension 1001 displayed a live interim transcript, then Answer kept the same Asterisk call connected and finalized one unread voicemail. A second synthetic caller phrase sent after Answer did not change the saved WAV. A simultaneous Answer/caller-hangup test created one message and returned the call screen to **Ready for internet calls**. The app did not crash in either test. Inbox playback, seek, transcript display, and the matching Recent call link passed. The caller-to-recipient path was exercised; recipient speech back to the caller was not verified because the Baresip host had no working ALSA playback device.
- A controlled Baresip caller reached Asterisk extension 1001 while it had no registered handset contact. Asterisk saved one new unread synthetic voicemail alongside two older messages. Two HTTPS inbox reads returned the same three IDs, and the downloaded WAV hash matched its metadata. This verifies server-side no-contact fallback and stable listing; the Android reconnection sync is still unverified.
- Live source checks on 2026-11-22 returned HTTP 404 from GitHub's latest stable release API and the official F-Droid per-app API. On the Moto G57 Power, **Settings → About → Updates** showed **No stable release yet** and **Not listed yet**, with separate successful-check times; daily checks remained off. Fixture tests cover both-source results, newer versions, missing listings, rate limits, and one-source failures.
- Zeus Asterisk is running on the LAN and the HTTPS mailbox port responds with HTTP 401 when no token is supplied. A controlled Baresip call previously left one unread test message while extension 1001 had no handset registered. Phone-side authenticated sync/playback of that staged server message remains unverified.
- The latest-source arm64 build was installed in place on the Moto over USB at version 0.1.5 / 1006; Android kept its original first-install time. A secure lab SIP line, trusted server certificate, and mailbox sync credential are now saved on the phone, but the line is not registered because its current Wi-Fi cannot reach Zeus at `192.168.0.14:5061` or `:8443` (ARP remains unresolved). A controlled Baresip 1002 caller is registered on Zeus and ready for the handset test. Same-call pickup, live transcript source isolation, handset offline-to-online delivery and exactly-once sync, and preservation of a real synced message remain unverified on the physical phone. Recipient speech back to the caller is also pending a working endpoint audio input/output path. A Galaxy and iPhone were not available, so their behavior is documented from official support pages rather than device-tested.

No GitHub stable release or F-Droid listing exists yet, so no APK download or F-Droid install is available. The locally built debug APK is signed with the debug key and is not suitable for publishing.

## Build

Install Android Studio or the Android command-line tools, an Android SDK platform for API 37, and a JDK supported by the Gradle wrapper. Then run:

```powershell
./gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To run the unit tests and lint check:

```powershell
./gradlew.bat testDebugUnitTest lintDebug
```

Builds use `arm64-v8a` by default. For an x86_64 emulator, pass `-PopencallerNativeAbi=x86_64`; the same pinned PJSIP and Mbed TLS source build is used for that ABI.

The stable release workflow derives Android version values from the release tag. For a local release build, pass the corresponding `opencallerVersionName` and `opencallerVersionCode` Gradle properties.

## License

OpenCaller is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).
