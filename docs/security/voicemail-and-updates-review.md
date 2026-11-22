# Voicemail and update security review

Reviewed 2026-11-22. Scope: SIP credentials, voicemail sync transport and storage, deletion behavior, live speech input, and the release/update metadata path.

## Controls reviewed

- SIP credentials and the voicemail sync bearer token are encrypted with an Android Keystore AES-GCM key. The server CA bundle is copied to app-private files, the app validates it for HTTPS and SIP TLS, and the app disables Android backup.
- The sync service is restricted to one configured Asterisk mailbox. It uses a constant-time bearer-token comparison, TLS 1.2 or newer, bounded metadata/audio reads, opaque SHA-256 message IDs, and does not log request paths or caller data. The example configuration says to keep it on a private LAN or VPN.
- The Android client rejects redirects for the mailbox service, validates the configured CA and hostname, checks the downloaded audio hash and size, stores message audio and transcripts in app-private files, and records deletion tombstones so a later sync does not restore a locally deleted item.
- Update checks read only the official GitHub Releases API and F-Droid display API. They compare numeric version codes; the F-Droid API is used for display only. Update links open only after a user tap. The app does not download or install packages, and cross-source upgrades remain disabled until signing compatibility is verified.
- Release metadata is read from the built APK's Gradle output metadata and contains only the application ID, version code, and version name.

## Findings and verification limits

- **Open, device-dependent speech input:** Android's `RecognizerIntent.EXTRA_AUDIO_SOURCE` documentation says a recognition service that does not support the extra may open the microphone instead. The app passes caller-only PCM through a pipe and keeps the SIP call microphone disconnected, but support has not yet been verified on the locked Moto G57 Power. Live transcription is off by default, and the settings screen explains this behavior. Do not describe transcription as verified until the on-device call test confirms caller audio appears without handset microphone capture.
- **Local deletion semantics:** Deleting a synchronized voicemail removes the phone's copy and retains a local tombstone to prevent it from being downloaded again. It does not erase the copy in Asterisk; the server copy can be deleted through the configured Asterisk voicemail tools. This is documented in the sync service guide.
- **Single mailbox service:** The companion HTTPS reader is intentionally scoped to one configured mailbox and bearer token. Operators who serve more than one user need a separate service instance and token for each mailbox; they should not share the token.
- **Cross-source signing:** Local same-source in-place installation succeeded with the existing signing certificate. A public GitHub release key and an F-Droid-signed package are not yet available to compare, so the app offers no cross-source package upgrade.
- **Public service exposure:** The LAN test server is reachable only on the local network used for testing. The service guide instructs operators not to expose port 8443 to the public internet. A public multi-tenant deployment has not been designed or reviewed.

## Current source status

On 2026-11-22, GitHub's latest stable-release API and F-Droid's official per-app API both returned HTTP 404. The API 37 emulator displayed **No stable release yet** for GitHub and **Not listed yet** for F-Droid, with independent successful-check timestamps and daily checks off. The update fixture suite covers current versions, either source being ahead, an unlisted package, missing GitHub metadata, rate limiting, and one source being offline. A controlled Baresip call to Asterisk extension 1001, with no handset contact registered, created one new unread voicemail. Repeated authenticated HTTPS listing returned the same three IDs and the audio hash matched. The LAN sync endpoint currently returns HTTP 401 without a bearer token. A synthetic in-app voicemail survived an in-place Moto upgrade from version 0.1.4 to 0.1.5 and remained playable; its test fixture was removed afterward. x86_64 emulator checks passed on API 26, 34, and 37 for PJSIP startup and synthetic voicemail playback/cleanup. Phone-side authenticated server sync, live pickup, live transcription, and a real server-message upgrade check remain pending because the Moto is locked. A synthetic server message remains staged for the next phone-side sync attempt.
