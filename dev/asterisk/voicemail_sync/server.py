#!/usr/bin/env python3
"""Small, account-scoped HTTPS reader for one Asterisk app_voicemail mailbox."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import ssl
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

MESSAGE_ID = re.compile(r"^[a-f0-9]{64}$")
MAX_AUDIO_BYTES = 20 * 1024 * 1024
MAX_METADATA_BYTES = 32 * 1024


@dataclass(frozen=True)
class VoicemailRecord:
    message_id: str
    audio_path: Path
    audio_sha256: str
    caller_address: str
    caller_name: str
    created_at_epoch_ms: int
    duration_ms: int
    unread: bool

    def as_json(self) -> dict[str, object]:
        return {
            "id": self.message_id,
            "audioSha256": self.audio_sha256,
            "callerAddress": self.caller_address,
            "callerName": self.caller_name,
            "createdAtEpochMs": self.created_at_epoch_ms,
            "durationMs": self.duration_ms,
            "unread": self.unread,
        }


def parse_metadata(path: Path) -> dict[str, str]:
    if path.is_symlink() or path.stat().st_size > MAX_METADATA_BYTES:
        return {}
    values: dict[str, str] = {}
    try:
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            key, separator, value = line.partition("=")
            if separator and key and len(key) <= 64:
                values[key.strip().lower()] = value.strip()[:512]
    except OSError:
        return {}
    return values


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as audio:
        while chunk := audio.read(128 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _caller_name(address: str) -> str:
    match = re.match(r'\s*"?([^"<]+?)"?\s*<[^>]+>\s*$', address)
    return match.group(1).strip()[:300] if match else "Internet caller"


def list_mailbox(voicemail_root: Path) -> list[VoicemailRecord]:
    """Read only INBOX/Old WAV + metadata pairs below the configured mailbox root."""
    root = voicemail_root.resolve(strict=True)
    records: dict[str, VoicemailRecord] = {}
    for folder_name in ("INBOX", "Old"):
        folder = root / folder_name
        if folder.is_symlink() or not folder.is_dir():
            continue
        for metadata_path in folder.glob("msg*.txt"):
            if metadata_path.is_symlink() or not metadata_path.is_file():
                continue
            audio_path = metadata_path.with_suffix(".wav")
            if audio_path.is_symlink() or not audio_path.is_file():
                continue
            try:
                audio_path.resolve(strict=True).relative_to(root)
                size = audio_path.stat().st_size
                if size <= 44 or size > MAX_AUDIO_BYTES:
                    continue
                metadata = parse_metadata(metadata_path)
                audio_sha256 = _sha256_file(audio_path)
                origtime = int(metadata.get("origtime", "0"))
                if origtime <= 0:
                    origtime = int(audio_path.stat().st_mtime)
                duration_seconds = int(metadata.get("duration", "0"))
                caller_address = metadata.get("callerid", "")[:300]
                message_id = hashlib.sha256(
                    f"opencaller:1001:{origtime}:{audio_sha256}".encode("utf-8")
                ).hexdigest()
                records[message_id] = VoicemailRecord(
                    message_id=message_id,
                    audio_path=audio_path,
                    audio_sha256=audio_sha256,
                    caller_address=caller_address,
                    caller_name=_caller_name(caller_address),
                    created_at_epoch_ms=origtime * 1000,
                    duration_ms=max(0, min(duration_seconds, 3600)) * 1000,
                    unread=folder_name == "INBOX",
                )
            except (OSError, ValueError):
                continue
    return sorted(records.values(), key=lambda record: (record.created_at_epoch_ms, record.message_id), reverse=True)


def is_authorized(provided_header: str | None, expected_token: str) -> bool:
    if not expected_token or not provided_header:
        return False
    scheme, separator, token = provided_header.partition(" ")
    return separator == " " and scheme.lower() == "bearer" and hmac.compare_digest(token, expected_token)


class VoicemailApiHandler(BaseHTTPRequestHandler):
    server_version = "OpenCallerVoicemail/1"
    sys_version = ""

    def do_GET(self) -> None:
        expected_token = self.server.sync_token  # type: ignore[attr-defined]
        if not is_authorized(self.headers.get("Authorization"), expected_token):
            self._json(401, {"error": "unauthorized"})
            return

        path = urlsplit(self.path).path
        if path == "/v1/inbox":
            records = list_mailbox(self.server.voicemail_root)  # type: ignore[attr-defined]
            self._json(200, {"messages": [record.as_json() for record in records]})
            return

        match = re.fullmatch(r"/v1/inbox/([a-f0-9]{64})/audio", path)
        if not match or not MESSAGE_ID.fullmatch(match.group(1)):
            self._json(404, {"error": "not_found"})
            return

        record = next(
            (item for item in list_mailbox(self.server.voicemail_root) if item.message_id == match.group(1)),  # type: ignore[attr-defined]
            None,
        )
        if record is None:
            self._json(404, {"error": "not_found"})
            return
        try:
            body = record.audio_path.read_bytes()
        except OSError:
            self._json(404, {"error": "not_found"})
            return
        if len(body) > MAX_AUDIO_BYTES:
            self._json(413, {"error": "too_large"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", f'"{record.audio_sha256}"')
        self.send_header("X-OpenCaller-Audio-SHA256", record.audio_sha256)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status_code: int, value: dict[str, object]) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        # Request paths can reveal message activity. Keep call data and bearer values out of logs.
        return


class VoicemailHttpsServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], mailbox_root: Path, token: str):
        super().__init__(address, VoicemailApiHandler)
        self.voicemail_root = mailbox_root
        self.sync_token = token


def serve() -> None:
    bind_address = os.environ.get("OPENCALLER_SYNC_BIND", "127.0.0.1")
    port = int(os.environ.get("OPENCALLER_SYNC_PORT", "8443"))
    mailbox_root = Path(os.environ.get("OPENCALLER_MAILBOX_ROOT", "/var/spool/asterisk/voicemail/opencaller/1001"))
    token = os.environ.get("OPENCALLER_SYNC_TOKEN_1001", "")
    certificate = Path(os.environ.get("OPENCALLER_SYNC_CERT", "/etc/opencaller-asterisk/asterisk/tls-test/server.crt"))
    private_key = Path(os.environ.get("OPENCALLER_SYNC_KEY", "/etc/opencaller-asterisk/asterisk/tls-test/server.key"))
    if not token or len(token) < 32:
        raise SystemExit("OPENCALLER_SYNC_TOKEN_1001 must be at least 32 characters")
    if not mailbox_root.is_dir() or not certificate.is_file() or not private_key.is_file():
        raise SystemExit("Voicemail directory or TLS certificate configuration is unavailable")

    server = VoicemailHttpsServer((bind_address, port), mailbox_root, token)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(certfile=certificate, keyfile=private_key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
