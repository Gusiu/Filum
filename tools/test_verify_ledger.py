"""Tests for verify_ledger.py: python -m unittest discover -s tools"""
import contextlib
import hashlib
import io
import json
import os
import pathlib
import shutil
import tempfile
import unittest

import verify_ledger

VECTORS = pathlib.Path(__file__).resolve().parent.parent / "docs" / "testvectors" / "paths.tsv"
MTIME = 1_756_400_000


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Backup:
    """A device directory written the way PROTOCOL §8-§9.2 describes the server's."""

    def __init__(self, root):
        self.device = os.path.join(root, "phone")
        self.meta = os.path.join(self.device, ".filum")
        os.makedirs(self.meta)
        self.lines = []

    def accept(self, path, content, stored=None):
        """Stores a file under files/ and appends its ledger line."""
        full = os.path.join(self.device, "files", *(stored or path).split("/"))
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as stream:
            stream.write(content)
        os.utime(full, (MTIME, MTIME))
        previous = verify_ledger.line_digest(self.lines[-1]) if self.lines else verify_ledger.GENESIS
        entry = {"n": len(self.lines) + 1, "at": MTIME, "p": path, "s": len(content),
                 "m": MTIME, "d": digest(content), "prev": previous}
        self.lines.append(json.dumps(entry, separators=(",", ":")))
        self.write_ledger()

    def file_away(self, stored_directory, name, content):
        """A previous version, filed under .filum/versions/ (PROTOCOL §8.3)."""
        directory = os.path.join(self.meta, "versions", *stored_directory.split("/"))
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, name), "wb") as stream:
            stream.write(content)

    def write_ledger(self, lines=None, newline="\n"):
        with open(os.path.join(self.meta, "integrity.jsonl"), "wb") as stream:
            stream.write("".join(line + newline for line in (lines or self.lines)).encode("utf-8"))

    def verify(self, **options):
        return verify_ledger.verify(self.device, **options)


class VerifyLedgerTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="filum-verify-")
        self.backup = Backup(self.root)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_an_untouched_backup_adds_up(self):
        self.backup.accept("DCIM/IMG_0001.JPG", b"photo")
        self.backup.accept("Documents/report.pdf", b"report")

        report = self.backup.verify()

        self.assertEqual([], report["problems"])
        self.assertEqual(2, report["entries"])
        self.assertEqual(2, report["matching"])

    def test_an_altered_line_breaks_the_chain_at_the_next_one(self):
        for index in range(3):
            self.backup.accept(f"f{index}.txt", f"content {index}".encode())
        lines = list(self.backup.lines)
        lines[1] = lines[1].replace(digest(b"content 1"), digest(b"substituted"))
        self.backup.write_ledger(lines)

        report = self.backup.verify(chain_only=True)

        self.assertIn("entry 3 does not point", report["chain"])

    def test_a_removed_line_is_reported_as_missing(self):
        for index in range(3):
            self.backup.accept(f"f{index}.txt", f"content {index}".encode())
        self.backup.write_ledger(self.backup.lines[:1] + self.backup.lines[2:])

        report = self.backup.verify(chain_only=True)

        self.assertIn("missing or was inserted", report["chain"])

    def test_line_endings_changed_by_an_editor_do_not_count_as_tampering(self):
        # The server reads lines the way .NET does: \r\n and \n alike.
        self.backup.accept("a.txt", b"a")
        self.backup.accept("b.txt", b"b")
        self.backup.write_ledger(newline="\r\n")

        self.assertEqual("intact", self.backup.verify(chain_only=True)["chain"])

    def test_a_file_changed_on_disk_is_reported(self):
        self.backup.accept("a.txt", b"original")
        with open(os.path.join(self.backup.device, "files", "a.txt"), "wb") as stream:
            stream.write(b"forged!!")
        os.utime(os.path.join(self.backup.device, "files", "a.txt"), (MTIME, MTIME))

        problems = self.backup.verify()["problems"]

        self.assertEqual(1, len(problems))
        self.assertIn("content differs", problems[0])

    def test_a_deleted_file_is_reported(self):
        self.backup.accept("a.txt", b"a")
        os.remove(os.path.join(self.backup.device, "files", "a.txt"))

        self.assertIn("not on disk", self.backup.verify()["problems"][0])

    def test_a_file_the_ledger_never_saw_is_reported(self):
        self.backup.accept("a.txt", b"a")
        with open(os.path.join(self.backup.device, "files", "planted.txt"), "wb") as stream:
            stream.write(b"x")

        self.assertIn("planted.txt: on disk but not in the ledger", self.backup.verify()["problems"])

    def test_encoded_names_are_read_back_as_the_phone_wrote_them(self):
        # PATH-RULES §5: the colon is stored as %3A, the phone's path keeps it.
        self.backup.accept("Documents/report:v2.docx", b"doc", stored="Documents/report%3Av2.docx")

        self.assertEqual([], self.backup.verify()["problems"])

    def test_a_collision_resolved_through_the_index_is_found(self):
        # PATH-RULES §6-§7: the second spelling of a name lands under ~hash, recorded in index.jsonl.
        self.backup.accept("Pictures/Photo.jpg", b"one")
        self.backup.accept("Pictures/photo.jpg", b"two", stored="Pictures/photo~6a8a8e.jpg")
        with open(os.path.join(self.backup.meta, "index.jsonl"), "w", encoding="utf-8") as stream:
            stream.write(json.dumps({"p": "Pictures/photo.jpg", "s": "Pictures/photo~6a8a8e.jpg", "r": "case_collision"}) + "\n")

        self.assertEqual([], self.backup.verify()["problems"])

    def test_a_previous_version_is_accounted_for_by_the_ledger(self):
        self.backup.accept("Notes/todo.txt", b"first")
        self.backup.file_away("Notes", "todo.20250828T211430Z.txt", b"first")
        self.backup.accept("Notes/todo.txt", b"second")

        report = self.backup.verify()

        self.assertEqual([], report["problems"])
        self.assertEqual(1, report["versions"])

    def test_a_version_the_ledger_never_recorded_is_reported(self):
        self.backup.accept("Notes/todo.txt", b"current")
        self.backup.file_away("Notes", "todo.20250828T211430Z.txt", b"never accepted")

        self.assertIn("no ledger entry", self.backup.verify()["problems"][0])

    def test_a_witnessed_head_must_still_be_there(self):
        for index in range(3):
            self.backup.accept(f"f{index}.txt", f"content {index}".encode())
        head = (2, verify_ledger.line_digest(self.backup.lines[1]))

        self.assertEqual("matches", self.backup.verify(heads=[head], chain_only=True)["heads"][0]["result"])

        # Rebuilt from scratch, consistent in itself, but not the history that was witnessed.
        rebuilt = Backup(tempfile.mkdtemp(dir=self.root))
        for index in range(3):
            rebuilt.accept(f"f{index}.txt", f"rewritten {index}".encode())
        report = rebuilt.verify(heads=[head], chain_only=True)

        self.assertEqual("intact", report["chain"])
        self.assertIn("not the line that was witnessed", report["heads"][0]["result"])

    def test_the_command_line_exits_non_zero_when_something_does_not_add_up(self):
        self.backup.accept("a.txt", b"a")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, verify_ledger.main([self.backup.device]))

            os.remove(os.path.join(self.backup.device, "files", "a.txt"))
            self.assertEqual(1, verify_ledger.main([self.backup.device]))


class PathVectorsTest(unittest.TestCase):
    """The shared vectors (docs/testvectors/paths.tsv), read backwards."""

    @staticmethod
    def unescape(text):
        # The file's own escapes: \xHH is one byte, a lone backslash stands for itself.
        out, index = [], 0
        while index < len(text):
            if text.startswith("\\x", index) and len(text) >= index + 4:
                out.append(chr(int(text[index + 2:index + 4], 16)))
                index += 4
            else:
                out.append(text[index])
                index += 1
        return "".join(out)

    def test_decoding_a_stored_path_gives_back_the_phone_path(self):
        checked = 0
        for row in VECTORS.read_text(encoding="utf-8").splitlines():
            if not row or row.startswith("#"):
                continue
            original, expected, _ = row.split("\t", 2)
            # Rejected paths are never stored; ~hash names need index.jsonl, tested above.
            if expected.startswith("REJECT:") or "~" in expected:
                continue
            stored = self.unescape(expected)
            self.assertEqual(self.unescape(original), verify_ledger.phone_path(stored, {}), row)
            checked += 1
        self.assertGreater(checked, 20)


if __name__ == "__main__":
    unittest.main()
