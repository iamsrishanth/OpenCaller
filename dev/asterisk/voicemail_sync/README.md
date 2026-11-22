# Asterisk voicemail sync service

This small HTTPS service lets OpenCaller fetch voicemail left by Asterisk while the handset was offline. It reads one fixed mailbox, `opencaller/1001`, and exposes only message metadata and WAV audio. It does not expose Asterisk's filesystem paths or AMI/ARI interfaces.

## Configure the phone and server

1. Generate the local lab `.env` with `scripts/new-local-env.ps1`. It creates a separate, high-entropy `OPENCALLER_VOICEMAIL_SYNC_TOKEN_1001`; keep `.env` private.
2. Run the service on the Asterisk host with the environment values below. Bind it to the private LAN or VPN address, and use a server certificate signed by the same CA the phone trusts for the SIP line. The certificate name must match the host name or IP entered in OpenCaller.
3. In **Settings → Internet line**, enter the HTTPS port (default `8443`) and the sync token. The token is encrypted with Android Keystore. Leave the field blank to keep a saved token; use **Disable voicemail sync** to remove it.
4. Keep the HTTPS port reachable only from your private LAN or VPN. Do not forward it from a router to the public internet.

The service requires Python 3 and uses only the standard library. Example environment values:

```text
OPENCALLER_SYNC_BIND=192.168.1.20
OPENCALLER_SYNC_PORT=8443
OPENCALLER_MAILBOX_ROOT=/var/spool/asterisk/voicemail/opencaller/1001
OPENCALLER_SYNC_CERT=/etc/asterisk/tls/server.crt
OPENCALLER_SYNC_KEY=/etc/asterisk/tls/server.key
OPENCALLER_SYNC_TOKEN_1001=<private token from .env>
```

Adjust the mailbox path and certificate paths for the host. Run the service as a dedicated unprivileged account that can read the fixed mailbox and certificate key but cannot change Asterisk files. Use systemd filesystem and IP address restrictions to keep access private. The app calls `GET /v1/inbox` and `GET /v1/inbox/{opaque-id}/audio` with TLS certificate validation and bearer authentication.

When the phone is unavailable, Asterisk plays the mailbox's own unavailable greeting. OpenCaller currently keeps its recorded greeting on the phone and does not upload it to Asterisk; record or replace the server greeting with Asterisk's voicemail tools if you want the same wording for offline calls.

Message IDs are derived from Asterisk's original timestamp and the WAV content hash, so moving a message from INBOX to Old does not make it appear twice. OpenCaller commits each downloaded WAV and metadata atomically in private app storage and checks the audio hash before saving. A local deletion is kept suppressed during later syncs; it removes the copy on the phone, while the server mailbox remains available through Asterisk's own voicemail tools.

## Validate the service

Run the isolated fixture tests from the repository root:

```powershell
py -3 -m unittest discover -s dev/asterisk/voicemail_sync -p 'test_*.py' -v
```

The tests cover bearer-token checks, complete message pairs, stable IDs across the INBOX-to-Old move, and rejection of oversized audio. The Android repository tests cover safe local commits, server-message deduplication, and deletion tombstones.
