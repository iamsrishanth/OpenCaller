import tempfile
import unittest
import wave
from pathlib import Path

from server import is_authorized, list_mailbox


def add_message(folder: Path, name: str, timestamp: int, caller: str = '"Test Caller" <1002>', duration: int = 4) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    with wave.open(str(folder / f"{name}.wav"), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        output.writeframes(b"\0\0" * 8000 * duration)
    (folder / f"{name}.txt").write_text(
        f'origtime={timestamp}\nduration={duration}\ncallerid={caller}\n',
        encoding="utf-8",
    )


class VoicemailSyncServerTests(unittest.TestCase):
    def test_message_is_stable_when_moved_from_inbox_to_old(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "1001"
            inbox = root / "INBOX"
            add_message(inbox, "msg0000", 1_800_000_000)

            unread = list_mailbox(root)
            self.assertEqual(1, len(unread))
            self.assertTrue(unread[0].unread)
            self.assertEqual("Test Caller", unread[0].caller_name)
            self.assertEqual("\"Test Caller\" <1002>", unread[0].caller_address)
            self.assertEqual(4_000, unread[0].duration_ms)

            old = root / "Old"
            old.mkdir()
            (inbox / "msg0000.wav").rename(old / "msg0000.wav")
            (inbox / "msg0000.txt").rename(old / "msg0000.txt")

            moved = list_mailbox(root)
            self.assertEqual([unread[0].message_id], [moved[0].message_id])
            self.assertFalse(moved[0].unread)

    def test_incomplete_and_oversized_pairs_are_not_exposed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "1001"
            inbox = root / "INBOX"
            add_message(inbox, "msg0000", 1_800_000_000)
            (inbox / "msg0000.wav").unlink()
            add_message(inbox, "msg0001", 1_800_000_001)
            (inbox / "msg0001.wav").write_bytes(b"x" * (20 * 1024 * 1024 + 1))

            self.assertEqual([], list_mailbox(root))

    def test_authentication_requires_exact_bearer_token(self) -> None:
        self.assertFalse(is_authorized(None, "a" * 32))
        self.assertFalse(is_authorized("Basic " + "a" * 32, "a" * 32))
        self.assertFalse(is_authorized("Bearer " + "a" * 31 + "b", "a" * 32))
        self.assertTrue(is_authorized("Bearer " + "a" * 32, "a" * 32))


if __name__ == "__main__":
    unittest.main()
