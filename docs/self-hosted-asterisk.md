# Self-hosted Asterisk lab

This is a local SIP lab for trying calls and server-side voicemail before connecting a paid phone number. It runs a pinned Asterisk source release in a container and creates two authenticated SIP extensions:

- **1001** is the OpenCaller test line.
- **1002** is a second SIP client for making controlled test calls.

The dialplan lets the extensions call each other. If 1001 is offline or does not answer within 20 seconds, Asterisk sends the caller to mailbox 1001. The voicemail and credentials stay on the contributor's machine. No account with a calling provider or paid number is needed for extension-to-extension tests.

OpenCaller now has a TLS-only SIP line setup screen, internet-call controls, voicemail settings, and a private voicemail inbox. The Docker Compose recipe below is still UDP-only and does not configure the TLS/SRTP transport or HTTPS sync reader required by the app. Use it to test Asterisk with another SIP softphone and its server-side voicemail; use a separately secured Asterisk configuration to connect OpenCaller.

## Start the lab

Use Docker Engine with Compose v2 on Linux or macOS, or Docker Desktop with Linux containers. This Windows checkout has no Docker engine or installed WSL distribution, so this Compose recipe has not been built or run here. A separate, LAN-only source build was exercised on a Linux lab host; its server-side result is recorded below.

In PowerShell:

```powershell
Set-Location dev/asterisk
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\new-local-env.ps1
docker compose up --build -d
docker compose ps
docker compose logs -f asterisk
```

The script writes fresh SIP passwords and voicemail PINs to `dev/asterisk/.env`. That file is ignored by Git. The script refuses to replace an existing `.env`, does not print credentials, and defaults to localhost-only access. `.env.example` contains placeholders only.

To stop Asterisk while preserving the mailbox and Asterisk database volumes:

```powershell
docker compose down
```

To start it again, run `docker compose up -d`. Asterisk is built from upstream version **22.12.0** and the Dockerfile checks the source archive's SHA-256 before compiling. The base image is Debian Bookworm. Asterisk 22 is an actively supported LTS series; the exact source release and checksum are pinned in `dev/asterisk/compose.yaml` and `dev/asterisk/Dockerfile`.

## Register the two SIP clients

Use any SIP softphone that supports authenticated UDP registration and the G.711 μ-law (`PCMU`) codec. For each account, enter:

| Setting | Extension 1001 | Extension 1002 |
| --- | --- | --- |
| User / auth name | `1001` | `1002` |
| Password | `OPENCALLER_SIP_PASSWORD_1001` from local `.env` | `OPENCALLER_SIP_PASSWORD_1002` from local `.env` |
| SIP port | `5060` | `5060` |
| Transport | UDP | UDP |
| Codec | G.711 μ-law / PCMU | G.711 μ-law / PCMU |

Use the address given in the next section. When both clients show as registered, call `1002` from `1001`, then call `1001` from `1002`. Calls between the extensions stay on this Asterisk instance.

The current OpenCaller app does not connect to this UDP-only Compose profile. Its Internet line settings require a TLS SIP port, a trusted CA certificate, and SRTP-capable media. The second test account can remain in a softphone using the UDP settings above. Do not use the example placeholders as passwords.

## Connect a phone on your local network

By default, Docker publishes SIP and RTP only on `127.0.0.1`. This is suitable for a softphone running on the same host. A phone or Android emulator cannot reach the host through its own loopback address.

For a physical phone on the same trusted Wi-Fi, use the host computer's private IPv4 address for both the bind and advertised addresses. On the first setup, run this in place of the default credential-generation command above:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\new-local-env.ps1 -BindAddress '192.168.1.20' -AdvertisedAddress '192.168.1.20'
```

Replace `192.168.1.20` with the host's actual LAN address. If your Docker setup requires publishing on all host interfaces, use `-BindAddress '0.0.0.0'` and keep the advertised address set to the host's private LAN IP. Allow inbound UDP `5060` and `10000-10100` only on the Windows **Private** firewall profile. Do not forward those ports from your router to the internet.

The generator deliberately refuses to overwrite credentials. If you already generated a localhost `.env`, edit only its `OPENCALLER_BIND_ADDRESS` and `OPENCALLER_ADVERTISED_ADDRESS` values to use the LAN addresses above; keep the generated passwords and PINs unchanged.

For the standard Android emulator, use the host bridge address as the advertised address. On the first setup, generate the file with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\new-local-env.ps1 -BindAddress '0.0.0.0' -AdvertisedAddress '10.0.2.2'
```

If `.env` already exists, edit those two address values instead of rerunning the generator.

This lab uses plain SIP and RTP over UDP. Keep it on localhost or a trusted private network. It is not configured for internet exposure, TLS, or SRTP. If Docker reports that `172.30.0.0/24` conflicts with a network already in use, change the subnet in `dev/asterisk/compose.yaml` and the matching `local_net` in `dev/asterisk/config/pjsip.conf.template` to another unused private subnet, then rebuild.

## Test voicemail with 1001 offline

1. Confirm 1001 and 1002 are registered.
2. Unregister or turn off the 1001 SIP client. Keep 1002 registered.
3. Call `1001` from 1002. If 1001 has no registered contact, Asterisk sends the caller directly to mailbox 1001. If 1001 is registered but does not answer, Asterisk rings it for up to 20 seconds before fallback. The mailbox plays its unavailable greeting and tone before recording.
4. To hear the message from a SIP client, dial `*98`, enter the 1001 voicemail PIN from the ignored `.env`, and follow Asterisk's mailbox menu. That menu can also record or replace the mailbox greeting.

The greeting, tone, and message recording run on Asterisk, so the caller can leave a message while the OpenCaller handset is offline. Asterisk stores mailbox data in a named Docker volume. The lab keeps that volume across `docker compose down` and rebuilds.

Useful diagnostics:

```powershell
docker compose exec asterisk asterisk -rx 'pjsip show endpoints'
docker compose exec asterisk asterisk -rx 'pjsip show endpoint 1001'
docker compose logs -f asterisk
```

The mailbox test uses Asterisk's PIN-protected `VoiceMailMain()` menu. To sync messages into OpenCaller, configure the separate authenticated HTTPS reader documented in [`dev/asterisk/voicemail_sync/README.md`](../dev/asterisk/voicemail_sync/README.md). The Compose recipe above does not start that reader; never expose the Asterisk spool directory or management interfaces directly to the phone.

### Server-only verification (2026-11-22)

An isolated Asterisk 22.12.0 source build ran on Debian 13, in a separate `/opt/opencaller-asterisk` prefix and `/etc/opencaller-asterisk` configuration. A systemd IP filter restricted the service to the private LAN; SIP bound to the host's LAN address on UDP 5060, with RTP configured for UDP 10000-10100. AMI, ARI, and the HTTP interface had no listeners. The existing system Asterisk service, `/etc/asterisk`, and its data paths were left unchanged. This temporary lab service was active for the test and was not enabled at boot.

Baresip 1.0.0 on a second Linux Mint 22.3 host registered extension 1002. With 1001 offline, the no-contact fallback played the unavailable greeting and beep; synthetic audio from 1002 was recorded and accepted with `#`. Asterisk reported one new message in 1001's INBOX. The saved mono 8 kHz PCM WAV was 12.20 seconds and decoded successfully with FFmpeg. The PIN-protected `*98` menu then loaded the message for playback, after which Asterisk moved it to the `Old` folder and kept the mailbox total at one. Baresip used a null audio sink, so this verifies the server playback path and media handling, not speaker-level listening quality.

The Linux test used an isolated source-built service, not the Docker Compose recipe above. It did not test two registered extensions calling each other, an Android SIP call, live voicemail handoff, the Android inbox, or offline sync. No public phone number or real caller audio was used.

## Connect a public phone number later

Extension calls in this lab do not use the public telephone network. An operator who wants a public number must arrange a DID and SIP trunk with a provider. Providers differ in registration, authentication, codec, firewall, and encryption requirements, so use the selected provider's current configuration instructions.

At a high level, the operator will need to:

1. Give Asterisk a stable public address or a VPN/private route, a DNS name, and certificates if the provider supports SIP over TLS and SRTP.
2. Add provider-specific `registration`, `auth`, `aor`, `endpoint`, and (where needed) `identify` objects to `pjsip.conf`.
3. Route the provider's inbound DID to extension 1001 in a separate provider-only dialplan context. Keep outbound destinations restricted to the intended users and calling plan.
4. Configure NAT and firewall rules only for the provider's documented source addresses and media ports. Keep AMI, ARI, and the Asterisk HTTP interface private; this lab disables them and publishes no management ports.
5. Check provider charges, emergency-calling behavior, recording/voicemail obligations, and forwarding rules before use. If forwarding a carrier SIM number to the DID, verify how that carrier handles the original caller ID and forwarding delay; it varies by carrier and plan.

The sample container settings are deliberately LAN-only and unencrypted. Do not turn this development configuration into an internet-facing PBX by opening UDP 5060 or the RTP range on a router.

## References

- [Asterisk supported versions](https://docs.asterisk.org/About-the-Project/Asterisk-Versions/)
- [Asterisk source release guidance](https://docs.asterisk.org/Getting-Started/Installing-Asterisk/Installing-Asterisk-From-Source/What-to-Download/)
- [PJSIP endpoint and authentication examples](https://docs.asterisk.org/Configuration/Channel-Drivers/SIP/Configuring-res_pjsip/res_pjsip-Configuration-Examples/)
- [Asterisk voicemail applications](https://docs.asterisk.org/Configuration/Applications/Voicemail/)
- [PJSIP NAT configuration](https://docs.asterisk.org/Configuration/Channel-Drivers/SIP/Configuring-res_pjsip/Configuring-res_pjsip-to-work-through-NAT/)
