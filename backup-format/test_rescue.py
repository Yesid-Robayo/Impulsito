# -*- coding: utf-8 -*-
"""Pruebas de rescue.py contra los vectores versionados. Necesitan Tink para Python, como la herramienta.

    python -m unittest test_rescue -v

Corren solas, sin la app ni Gradle. Cubren la derivación del código, cada copia y cada alteración del JSON, una copia de
varios segmentos hecha en el momento, y lo que la herramienta promete a la persona: no mostrar el código, no reemplazar un
archivo y no dejar temporales.
"""
import base64
import contextlib
import gzip
import hashlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_vectors  # noqa: E402
import rescue  # noqa: E402

VECTORS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors", "format-v2-vectors.json")


def load_vectors():
    with open(VECTORS, encoding="utf-8") as file:
        return json.load(file)


def open_copy(data, printed, media=False):
    try:
        _, total, sha256 = rescue.rescue(io.BytesIO(data), rescue.parse_code(printed), io.BytesIO(), media)
    except rescue.Rejected as rejected:
        return rejected.reason, None, None
    return "OK", total, sha256


class CodeTest(unittest.TestCase):
    def setUp(self):
        self.vectors = load_vectors()

    def test_derivation_matches_the_published_values(self):
        for entry in self.vectors["codes"]:
            backup_key, key_id = rescue.derive(entry["canonical"])
            self.assertEqual(entry["key_id"], key_id.hex())
            self.assertEqual(entry["bk_sha256"], hashlib.sha256(backup_key).hexdigest())
            self.assertEqual(entry["check"], rescue.check_characters(entry["canonical"]))
            self.assertEqual(entry["printed"], rescue.printable(entry["canonical"]))

    def test_typed_codes_give_their_result(self):
        for entry in self.vectors["typed_codes"]:
            self.assertEqual((entry["result"], entry["canonical"]), rescue.classify_code(entry["typed"]), entry["typed"])

    def test_a_typo_in_any_position_is_caught_by_the_check_characters(self):
        printed = self.vectors["codes"][0]["printed"]
        compact = printed.replace("-", "")
        original_key_id = rescue.derive(rescue.parse_code(printed))[1]
        tried, missed = 0, 0
        for position in range(rescue.CODE_LENGTH):
            for replacement in rescue.ALPHABET:
                if replacement == compact[position]:
                    continue
                tried += 1
                typed = compact[:position] + replacement + compact[position + 1:]
                if rescue.classify_code(typed)[0] != "CHECK_MISMATCH":
                    # 10 bits de control dejan pasar uno de cada 1.024 al azar. El que pasa da otro key_id, así que
                    # la copia dice "otro código" y no se abre ni se toca.
                    missed += 1
                    self.assertNotEqual(original_key_id, rescue.derive(rescue.parse_code(typed))[1])
        self.assertEqual(rescue.CODE_LENGTH * (len(rescue.ALPHABET) - 1), tried)
        self.assertLessEqual(missed, tried // 100)


class VectorTest(unittest.TestCase):
    def setUp(self):
        self.vectors = load_vectors()
        self.copies = {entry["name"]: entry for entry in self.vectors["copies"]}

    def test_each_copy_gives_its_expected_result(self):
        for entry in self.vectors["copies"]:
            data = base64.b64decode(entry["file_base64"])
            self.assertEqual(entry["file_sha256"], hashlib.sha256(data).hexdigest(), entry["name"])
            result, total, sha256 = open_copy(data, entry["code"], entry["kind"] == "media")
            self.assertEqual(entry["expected"], result, entry["name"])
            if result == "OK":
                self.assertEqual((entry["plain_bytes"], entry["plain_sha256"]), (total, sha256), entry["name"])

    def test_each_mutation_gives_its_expected_result(self):
        for entry in self.vectors["mutations"]:
            base = self.copies[entry["base"]]
            data = make_vectors.apply(entry["operation"], base64.b64decode(base["file_base64"]), self.copies)
            result, _, _ = open_copy(data, entry["code"], base["kind"] == "media")
            self.assertEqual(entry["expected"], result, entry["name"])

    def test_header_fields_are_read_as_published(self):
        data = base64.b64decode(self.copies["database-basic"]["file_base64"])
        _, fields = rescue.parse_header(io.BytesIO(data))
        parameters = self.vectors["parameters"]
        self.assertEqual(68, fields["header_len"])
        self.assertEqual(7, fields["generation"])
        self.assertEqual(1, fields["schema_version"])
        self.assertEqual(parameters["created_at_utc_ms"], fields["created_at_utc_ms"])
        self.assertEqual(parameters["device_id"], fields["device_id"])
        self.assertEqual(self.vectors["codes"][0]["key_id"], fields["key_id"])
        self.assertEqual(self.copies["database-basic"]["plain_bytes"], fields["raw_size"])

    def test_overhead_is_header_tink_header_and_one_tag_per_segment(self):
        entry = self.copies["database-basic"]
        data = base64.b64decode(entry["file_base64"])
        sink = io.BytesIO()
        rescue.rescue(io.BytesIO(data), rescue.parse_code(entry["code"]), sink)
        compressed = len(gzip.compress(sink.getvalue(), 6, mtime=0))
        # Una sola corrida de gzip da el mismo largo con el mismo nivel: 68 de cabecera, 40 de Tink y 16 de etiqueta.
        self.assertEqual(68 + 40 + 16 + compressed, len(data))


class MultiSegmentTest(unittest.TestCase):
    """Una copia de cuatro segmentos, cifrada en el momento: el vector versionado es de un solo segmento."""

    @classmethod
    def setUpClass(cls):
        vectors = load_vectors()
        cls.printed = vectors["codes"][0]["printed"]
        # Bytes que gzip no comprime, para pasar de tres segmentos cifrados.
        seed = hashlib.sha256(b"multi-segment").digest()
        blocks = []
        for _ in range(3 * 1024 * 1024 // 32 + 1):
            seed = hashlib.sha256(seed).digest()
            blocks.append(seed)
        cls.raw = rescue.SQLITE_MAGIC + b"".join(blocks)
        key_id = rescue.derive(rescue.parse_code(cls.printed))[1]
        header = make_vectors.header(key_id, 11, 1, len(cls.raw))
        cls.data = make_vectors.encrypt(cls.printed, header, gzip.compress(cls.raw, 6, mtime=0))

    def test_opens_with_the_same_sha256(self):
        result, total, sha256 = open_copy(self.data, self.printed)
        self.assertEqual(("OK", len(self.raw), hashlib.sha256(self.raw).hexdigest()), (result, total, sha256))
        self.assertGreaterEqual(-(-(len(self.data) - 68) // rescue.CIPHERTEXT_SEGMENT_BYTES), 4)

    def test_truncating_or_reordering_segments_is_damage(self):
        segment = rescue.CIPHERTEXT_SEGMENT_BYTES
        first_end = 68 + segment
        data = self.data
        cases = {
            "first boundary": data[:first_end],
            "second boundary": data[:first_end + segment],
            "middle": data[:first_end + segment // 2],
            "swapped": data[:68] + data[first_end:first_end + segment] + data[68:first_end] + data[first_end + segment:],
            "last segment dropped": data[:first_end + 2 * segment],
        }
        for name, altered in cases.items():
            self.assertEqual("DAMAGED", open_copy(altered, self.printed)[0], name)


class CommandLineTest(unittest.TestCase):
    def setUp(self):
        self.vectors = load_vectors()
        self.copies = {entry["name"]: entry for entry in self.vectors["copies"]}
        self.folder = tempfile.mkdtemp(prefix="rescue-test-")

    def tearDown(self):
        for name in os.listdir(self.folder):
            os.remove(os.path.join(self.folder, name))
        os.rmdir(self.folder)

    def write_copy(self, name, extension=".slbk", data=None):
        path = os.path.join(self.folder, name + extension)
        with open(path, "wb") as file:
            file.write(data if data is not None else base64.b64decode(self.copies[name]["file_base64"]))
        return path

    def run_tool(self, arguments, typed):
        stdout, stderr = io.StringIO(), io.StringIO()
        stdin = io.StringIO(typed + "\n")
        with mock.patch.object(sys, "stdin", stdin), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = rescue.main(arguments)
        return status, stdout.getvalue() + stderr.getvalue()

    def assert_no_leftovers(self, expected):
        self.assertEqual(sorted(expected), sorted(os.listdir(self.folder)))

    def test_writes_the_database_and_never_shows_the_code(self):
        source = self.write_copy("database-basic")
        target = os.path.join(self.folder, "restored.db")
        code = self.copies["database-basic"]["code"]
        status, output = self.run_tool([source, "--out", target, "--lang", "en"], code)
        self.assertEqual(0, status)
        with open(target, "rb") as file:
            self.assertEqual(self.copies["database-basic"]["plain_sha256"], hashlib.sha256(file.read()).hexdigest())
        canonical = rescue.parse_code(code)
        for secret in (code, canonical, code.replace("-", ""), canonical[:8]):
            self.assertNotIn(secret, output)
        self.assertIn(self.copies["database-basic"]["plain_sha256"], output)
        self.assert_no_leftovers(["database-basic.slbk", "restored.db"])

    def test_a_photo_opens_by_its_extension(self):
        source = self.write_copy("media-basic", ".slmd")
        target = os.path.join(self.folder, "photo.webp")
        status, _ = self.run_tool([source, "--out", target], self.copies["media-basic"]["code"])
        self.assertEqual(0, status)
        self.assert_no_leftovers(["media-basic.slmd", "photo.webp"])

    def test_never_replaces_an_existing_file(self):
        source = self.write_copy("database-basic")
        target = os.path.join(self.folder, "restored.db")
        with open(target, "wb") as file:
            file.write(b"keep me")
        status, _ = self.run_tool([source, "--out", target], self.copies["database-basic"]["code"])
        self.assertEqual(rescue.EXIT_CODES["OUTPUT_EXISTS"], status)
        with open(target, "rb") as file:
            self.assertEqual(b"keep me", file.read())
        self.assert_no_leftovers(["database-basic.slbk", "restored.db"])

    def test_failures_leave_nothing_and_never_show_the_code(self):
        damaged = make_vectors.apply({"op": "xor", "offset": 120, "mask": 1},
                                     base64.b64decode(self.copies["database-basic"]["file_base64"]), self.copies)
        source = self.write_copy("damaged", data=damaged)
        code = self.copies["database-basic"]["code"]
        target = os.path.join(self.folder, "restored.db")
        cases = [
            (code, "DAMAGED"),
            (code[:-1] + "2", "CODE_INVALID"),
            (self.vectors["codes"][1]["printed"], "OTHER_CODE"),
        ]
        for typed, reason in cases:
            status, output = self.run_tool([source, "--out", target, "--lang", "es"], typed)
            self.assertEqual(rescue.EXIT_CODES[reason], status, reason)
            self.assertIn(reason, output)
            self.assertNotIn(rescue.normalize(typed)[:8], output)
            self.assert_no_leftovers(["damaged.slbk"])

    def test_messages_exist_in_the_three_languages(self):
        keys = set(MESSAGES_KEYS)
        for language, texts in rescue.MESSAGES.items():
            self.assertEqual(keys, set(texts), language)

    def test_language_comes_from_the_argument_or_the_system(self):
        with mock.patch.dict(os.environ, {"LANGUAGE": "", "LC_ALL": "", "LC_MESSAGES": "", "LANG": "pt_BR.UTF-8"}):
            self.assertEqual("pt", rescue.pick_language(None))
            self.assertEqual("es", rescue.pick_language("es"))


MESSAGES_KEYS = ["prompt", "ok"] + [reason for reason in rescue.EXIT_CODES if reason != "OK"]


if __name__ == "__main__":
    unittest.main()
