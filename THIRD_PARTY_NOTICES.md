# Third-party notices

OpenCaller links its optional Android SIP client with the native PJSIP stack and Mbed TLS. The build uses these pinned source revisions:

| Component | Revision | License | License text |
| --- | --- | --- | --- |
| PJSIP / PJProject | 2.17, `5a457451fa2712ba18e12b01738e8ff3af2b26fd` | GNU GPL version 2 or (at your option) any later version | [`third_party/pjproject/COPYING`](third_party/pjproject/COPYING) |
| Mbed TLS | 3.6.8, `82e69999f91126589a28b70dd75b69fbcabe2711` | Apache License 2.0 or GNU GPL version 2 or later; this build selects Apache-2.0 | [`third_party/mbedtls/LICENSE`](third_party/mbedtls/LICENSE) |
| Mbed TLS framework | `ced31491c5ec907a6be533512f19dc1779230ac1` | Apache License 2.0 or GNU GPL version 2 or later; this build selects Apache-2.0 | [`third_party/mbedtls/framework/LICENSE`](third_party/mbedtls/framework/LICENSE) |

PJSIP's GPL notice is compatible with OpenCaller's GPL-3.0-or-later license because the PJSIP source permits use under GPL version 2 or any later version. The full notices for other components distributed in the PJSIP source tree are retained with that source, including libSRTP, Speex, the resampler, WebRTC, libyuv, and GSM. Refer to their license files under `third_party/pjproject/third_party/` for the precise terms and required attribution.

The source repositories are [PJProject](https://github.com/pjsip/pjproject) and [Mbed TLS](https://github.com/Mbed-TLS/mbedtls). The Android native build is reproducible from the pinned revisions using [`scripts/build-pjsip-android.sh`](scripts/build-pjsip-android.sh).
