"""Tests for verify_ledger.py: python -m unittest discover -s tools"""
import base64
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

# PROTOCOL §3.1: a pin is the SHA-256 of a public key in base64url, 43 characters.
PIN = base64.urlsafe_b64encode(hashlib.sha256(b"the phone's public key").digest()).rstrip(b"=").decode()
PAIRED_AT = "2025-08-20T09:30:00.5273913+00:00"


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Backup:
    """A device directory written the way PROTOCOL §8-§9.2 describes the server's."""

    def __init__(self, root):
        self.device = os.path.join(root, "phone")
        self.meta = os.path.join(self.device, ".filum")
        os.makedirs(self.meta)
        self.lines = []
        # PROTOCOL §8.1: the device's record, written when it was paired.
        with open(os.path.join(self.meta, "device.json"), "w", encoding="utf-8") as stream:
            json.dump({"pin": PIN, "slug": "phone", "label": "Galaxy S24 Ultra",
                       "pairedAt": PAIRED_AT, "enabled": True}, stream, indent=2)

    def accept(self, path, content, stored=None, mtime=MTIME):
        """Stores a file under files/ and appends its ledger line."""
        full = os.path.join(self.device, "files", *(stored or path).split("/"))
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as stream:
            stream.write(content)
        os.utime(full, (mtime, mtime))
        previous = verify_ledger.line_digest(self.lines[-1]) if self.lines else verify_ledger.GENESIS
        entry = {"n": len(self.lines) + 1, "at": MTIME, "p": path, "s": len(content),
                 "m": mtime, "d": digest(content), "prev": previous}
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

    def provenance(self, path, **options):
        return verify_ledger.provenance(self.device, path, **options)


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

    def test_a_line_that_is_json_but_not_an_object_cannot_be_read(self):
        self.backup.accept("a.txt", b"a")
        self.backup.accept("b.txt", b"b")
        self.backup.write_ledger(self.backup.lines + ["42"])

        self.assertEqual("line 3 cannot be read", self.backup.verify(chain_only=True)["chain"])

    def test_a_line_that_is_not_an_object_does_not_stop_the_file_checks(self):
        # The chain breaks at line 1 already, so only the file checks read line 2.
        self.backup.accept("a.txt", b"a")
        self.backup.write_ledger([self.backup.lines[0].replace('"n":1', '"n":7'), "null"])

        report = self.backup.verify()

        self.assertIn("entry 7 where 1 was expected", report["chain"])
        self.assertEqual(1, report["matching"])

    def test_an_index_line_that_is_not_an_object_is_passed_over(self):
        self.backup.accept("Pictures/Photo.jpg", b"one")
        self.backup.accept("Pictures/photo.jpg", b"two", stored="Pictures/photo~6a8a8e.jpg")
        with open(os.path.join(self.backup.meta, "index.jsonl"), "w", encoding="utf-8") as stream:
            stream.write('["s", "p"]\n')
            stream.write(json.dumps({"p": "Pictures/photo.jpg", "s": "Pictures/photo~6a8a8e.jpg", "r": "case_collision"}) + "\n")

        self.assertEqual([], self.backup.verify()["problems"])

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


class ProvenanceTest(unittest.TestCase):
    """The report on one phone path: verify_ledger.py --provenance."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="filum-provenance-")
        self.backup = Backup(self.root)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_the_report_names_the_device_the_file_came_from(self):
        self.backup.accept("Notes/todo.txt", b"text")

        report = self.backup.provenance("Notes/todo.txt")

        self.assertEqual({"label": "Galaxy S24 Ultra", "pin": PIN, "pairedAt": PAIRED_AT}, report["device"])

    def test_an_unpaired_device_is_unknown_and_its_backup_still_adds_up(self):
        # PROTOCOL §8.1: unpairing removes device.json and nothing else.
        self.backup.accept("Notes/todo.txt", b"text")
        os.remove(os.path.join(self.backup.meta, "device.json"))

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = verify_ledger.main([self.backup.device, "--provenance", "Notes/todo.txt"])

        self.assertEqual(0, status)
        self.assertEqual("device: unknown - .filum/device.json is missing or cannot be read",
                         output.getvalue().splitlines()[0])
        self.assertIsNone(self.backup.provenance("Notes/todo.txt")["device"])

        # A record that cannot be read names no device either.
        with open(os.path.join(self.backup.meta, "device.json"), "w", encoding="utf-8") as stream:
            stream.write("not JSON")
        self.assertIsNone(self.backup.provenance("Notes/todo.txt")["device"])

    def test_every_acceptance_is_listed_with_where_its_version_lies(self):
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 7200)
        self.backup.accept("DCIM/IMG_0001.JPG", b"photo")
        # PROTOCOL §8.3: replaced, the first copy is filed away under its own modification time.
        self.backup.file_away("Notes", "todo.20250828T145320Z.txt", b"first")
        self.backup.accept("Notes/todo.txt", b"second", mtime=MTIME - 3600)

        report = self.backup.provenance("Notes/todo.txt")

        self.assertEqual([], report["problems"])
        self.assertEqual(
            [{"line": 1, "at": MTIME, "s": 5, "m": MTIME - 7200, "d": digest(b"first"),
              "locations": [".filum/versions/Notes/todo.20250828T145320Z.txt"], "content": "matches",
              "witnessed": None},
             {"line": 3, "at": MTIME, "s": 6, "m": MTIME - 3600, "d": digest(b"second"),
              "locations": ["files/Notes/todo.txt"], "content": "matches", "witnessed": None}],
            report["acceptances"])
        self.assertEqual([], report["others"])

    def test_versions_holding_the_same_content_are_told_apart_by_their_names(self):
        # first, second, first again, third: two versions hold "first", each line finds its own.
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 10800)
        self.backup.file_away("Notes", "todo.20250828T135320Z.txt", b"first")
        self.backup.accept("Notes/todo.txt", b"second", mtime=MTIME - 7200)
        self.backup.file_away("Notes", "todo.20250828T145320Z.txt", b"second")
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 3600)
        self.backup.file_away("Notes", "todo.20250828T155320Z.txt", b"first")
        self.backup.accept("Notes/todo.txt", b"third")

        report = self.backup.provenance("Notes/todo.txt")

        self.assertEqual([], report["problems"])
        self.assertEqual(
            [[".filum/versions/Notes/todo.20250828T135320Z.txt"],
             [".filum/versions/Notes/todo.20250828T145320Z.txt"],
             [".filum/versions/Notes/todo.20250828T155320Z.txt"],
             ["files/Notes/todo.txt"]],
            [acceptance["locations"] for acceptance in report["acceptances"]])

    def test_a_copy_whose_content_changed_is_reported_where_it_lies(self):
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 7200)
        self.backup.file_away("Notes", "todo.20250828T145320Z.txt", b"forged")
        self.backup.accept("Notes/todo.txt", b"second")
        with open(os.path.join(self.backup.device, "files", "Notes", "todo.txt"), "wb") as stream:
            stream.write(b"forged too")

        report = self.backup.provenance("Notes/todo.txt")

        self.assertEqual(
            [("differs", [".filum/versions/Notes/todo.20250828T145320Z.txt"]), ("differs", ["files/Notes/todo.txt"])],
            [(acceptance["content"], acceptance["locations"]) for acceptance in report["acceptances"]])
        self.assertEqual(
            ["line 1: the content at .filum/versions/Notes/todo.20250828T145320Z.txt is not what was accepted",
             "line 2: the content at files/Notes/todo.txt is not what was accepted"],
            report["problems"])

    def test_a_current_copy_with_another_modification_time_is_reported_as_verify_reports_it(self):
        # PROTOCOL §8.2: the server sets the accepted modification time on commit. The same
        # file must get the same verdict whichever way the backup is checked.
        self.backup.accept("Notes/todo.txt", b"text")
        os.utime(os.path.join(self.backup.device, "files", "Notes", "todo.txt"), (MTIME + 60, MTIME + 60))

        expected = ["Notes/todo.txt: the modification time differs from the ledger's latest entry (1)"]
        self.assertEqual(expected, self.backup.verify()["problems"])
        self.assertEqual(expected, self.backup.provenance("Notes/todo.txt")["problems"])

    def test_a_current_copy_holding_an_earlier_lines_content_is_not_taken_for_the_latest(self):
        # The first version copied back over the current one by hand: what line 2 accepted is
        # gone, and the current copy is accounted for by line 1.
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 7200)
        self.backup.file_away("Notes", "todo.20250828T145320Z.txt", b"first")
        self.backup.accept("Notes/todo.txt", b"second")
        with open(os.path.join(self.backup.device, "files", "Notes", "todo.txt"), "wb") as stream:
            stream.write(b"first")

        report = self.backup.provenance("Notes/todo.txt")

        self.assertEqual(("missing", []), (report["acceptances"][1]["content"], report["acceptances"][1]["locations"]))
        self.assertEqual([{"location": "files/Notes/todo.txt", "content_of": [1]}], report["others"])
        self.assertEqual(["line 2: the content accepted is not on disk"], report["problems"])

    def test_a_version_that_is_gone_is_reported(self):
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 7200)
        self.backup.accept("Notes/todo.txt", b"second")

        report = self.backup.provenance("Notes/todo.txt")

        first = report["acceptances"][0]
        self.assertEqual(("missing", []), (first["content"], first["locations"]))
        self.assertEqual(["line 1: the content accepted is not on disk"], report["problems"])

    def test_a_file_sent_again_after_it_was_deleted_is_found_in_the_current_copy(self):
        # Deleted by hand from files/, the file is sent again: two lines, one copy, no version.
        self.backup.accept("Notes/todo.txt", b"same")
        self.backup.accept("Notes/todo.txt", b"same")

        report = self.backup.provenance("Notes/todo.txt")

        self.assertEqual([], report["problems"])
        self.assertEqual([["files/Notes/todo.txt"], ["files/Notes/todo.txt"]],
                         [acceptance["locations"] for acceptance in report["acceptances"]])

    def test_a_file_holding_content_no_line_accepted_is_reported(self):
        self.backup.accept("Notes/todo.txt", b"current")
        self.backup.file_away("Notes", "todo.20250101T000000Z.txt", b"planted")

        report = self.backup.provenance("Notes/todo.txt")

        self.assertEqual([{"location": ".filum/versions/Notes/todo.20250101T000000Z.txt", "content_of": []}],
                         report["others"])
        self.assertEqual(
            [".filum/versions/Notes/todo.20250101T000000Z.txt: no ledger entry for Notes/todo.txt has this content"],
            report["problems"])

    def test_the_phone_path_is_followed_to_the_name_a_collision_gave_it(self):
        # PATH-RULES §6-§7: photo.jpg met Photo.jpg on disk and is stored as photo~6a8a8e.jpg.
        self.backup.accept("Pictures/Photo.jpg", b"another file")
        self.backup.accept("Pictures/photo.jpg", b"one", stored="Pictures/photo~6a8a8e.jpg", mtime=MTIME - 7200)
        self.backup.file_away("Pictures", "photo~6a8a8e.20250828T145320Z.jpg", b"one")
        self.backup.accept("Pictures/photo.jpg", b"two", stored="Pictures/photo~6a8a8e.jpg")
        with open(os.path.join(self.backup.meta, "index.jsonl"), "w", encoding="utf-8") as stream:
            stream.write(json.dumps({"p": "Pictures/photo.jpg", "s": "Pictures/photo~6a8a8e.jpg", "r": "case_collision"}) + "\n")

        report = self.backup.provenance("Pictures/photo.jpg")

        self.assertEqual([], report["problems"])
        self.assertEqual(
            [[".filum/versions/Pictures/photo~6a8a8e.20250828T145320Z.jpg"], ["files/Pictures/photo~6a8a8e.jpg"]],
            [acceptance["locations"] for acceptance in report["acceptances"]])

    def test_lines_up_to_a_witnessed_head_are_witnessed_and_later_ones_are_not(self):
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 7200)
        self.backup.file_away("Notes", "todo.20250828T145320Z.txt", b"first")
        self.backup.accept("a.txt", b"a")
        head = (2, verify_ledger.line_digest(self.backup.lines[1]))
        self.backup.accept("Notes/todo.txt", b"second")

        report = self.backup.provenance("Notes/todo.txt", heads=[head])

        self.assertEqual((1, 2), report["heads"][0]["lines"])
        self.assertEqual([True, False], [acceptance["witnessed"] for acceptance in report["acceptances"]])

    def test_a_head_vouches_only_for_the_lines_still_linked_to_it(self):
        # PROTOCOL §9.5: line 1 is altered, so line 2 no longer points at it. Line 3 is still the
        # line that was witnessed, but it vouches for lines 2-3 only.
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 7200)
        self.backup.file_away("Notes", "todo.20250828T145320Z.txt", b"first")
        self.backup.accept("Notes/todo.txt", b"second")
        self.backup.accept("a.txt", b"a")
        head = (3, verify_ledger.line_digest(self.backup.lines[2]))
        lines = list(self.backup.lines)
        lines[0] = lines[0].replace(f'"at":{MTIME}', f'"at":{MTIME + 1}')
        self.backup.write_ledger(lines)

        report = self.backup.provenance("Notes/todo.txt", heads=[head])

        self.assertIn("entry 2 does not point", report["chain"])
        self.assertEqual(("matches", (2, 3)), (report["heads"][0]["result"], report["heads"][0]["lines"]))
        self.assertEqual([False, True], [acceptance["witnessed"] for acceptance in report["acceptances"]])

    def test_the_command_prints_each_acceptance_where_it_lies_and_whether_it_is_witnessed(self):
        self.backup.accept("Notes/todo.txt", b"first", mtime=MTIME - 7200)
        self.backup.file_away("Notes", "todo.20250828T145320Z.txt", b"first")
        self.backup.accept("Notes/todo.txt", b"second")
        head = f"2:{verify_ledger.line_digest(self.backup.lines[1])}"

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = verify_ledger.main([self.backup.device, "--provenance", "Notes/todo.txt", "--head", head])

        self.assertEqual(0, status)
        self.assertEqual([
            f"device: Galaxy S24 Ultra, key pin {PIN}, paired {PAIRED_AT}",
            "ledger: 2 entries, chain intact",
            "head 2: matches, lines 1-2 as witnessed",
            "Notes/todo.txt: accepted 2 time(s)",
            "line 1: accepted 2025-08-28T16:53:20Z, 5 bytes, modified 2025-08-28T14:53:20Z",
            f"  sha256 {digest(b'first')}",
            "  .filum/versions/Notes/todo.20250828T145320Z.txt: content matches",
            "  witnessed",
            "line 2: accepted 2025-08-28T16:53:20Z, 6 bytes, modified 2025-08-28T16:53:20Z",
            f"  sha256 {digest(b'second')}",
            "  files/Notes/todo.txt: content matches",
            "  witnessed",
            "OK",
        ], output.getvalue().splitlines())

    def test_the_command_exits_non_zero_for_a_path_never_accepted(self):
        self.backup.accept("a.txt", b"a")

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = verify_ledger.main([self.backup.device, "--provenance", "b.txt", "--json"])

        self.assertEqual(1, status)
        self.assertEqual(["b.txt: not in the ledger and not on disk"], json.loads(output.getvalue())["problems"])

    def test_the_command_takes_provenance_or_chain_only_not_both(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            verify_ledger.main([self.backup.device, "--provenance", "a.txt", "--chain-only"])

        self.assertEqual(2, raised.exception.code)


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
