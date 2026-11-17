# -*- coding: utf-8 -*-
"""Pruebas del verificador de hosts, sobre APK armados aquí mismo con datos inventados.

    python -I -m unittest discover -s tools/verification -p "test_*.py"

Cada APK es un ZIP con un DEX mínimo (solo su tabla de cadenas), una tabla de recursos y un manifiesto
compilado mínimos, y archivos crudos. No hace falta Android ni ninguna biblioteca.
"""
import contextlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import apk_hosts  # noqa: E402

ANDROID_NS = "http://schemas.android.com/apk/res/android"


def uleb(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def dex(strings):
    """Un DEX con solo su cabecera y su tabla de cadenas, como la escribe d8."""
    header = bytearray(0x70)
    header[0:8] = b"dex\n035\x00"
    table = 0x70
    data_start = table + 4 * len(strings)
    offsets = []
    data = bytearray()
    for text in strings:
        offsets.append(data_start + len(data))
        encoded = text.encode("utf-8")
        data += uleb(len(text)) + encoded + b"\x00"
    struct.pack_into("<II", header, 0x38, len(strings), table)
    return bytes(header) + b"".join(struct.pack("<I", o) for o in offsets) + bytes(data)


def string_pool(strings, utf8=True):
    """Un bloque `ResStringPool`, en UTF-8 o en UTF-16."""
    body = bytearray()
    offsets = []
    for text in strings:
        offsets.append(len(body))
        if utf8:
            encoded = text.encode("utf-8")
            body += bytes([len(text), len(encoded)]) + encoded + b"\x00"
        else:
            body += struct.pack("<H", len(text)) + text.encode("utf-16-le") + b"\x00\x00"
    while len(body) % 4:
        body.append(0)
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(body)
    flags = 1 << 8 if utf8 else 0
    header = struct.pack("<HHIIIIII", 0x0001, header_size, size, len(strings), 0, flags, strings_start, 0)
    return header + b"".join(struct.pack("<I", o) for o in offsets) + bytes(body)


def resource_table(strings, utf8=True):
    pool = string_pool(strings, utf8)
    return struct.pack("<HHII", 0x0002, 12, 12 + len(pool), 1) + pool


def binary_xml(strings):
    pool = string_pool(strings)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(pool)) + pool


class ApkHostsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def build(self, dex_strings=(), arsc_strings=(), files=None, utf8=True):
        path = os.path.join(self.dir.name, "app-release.apk")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", binary_xml([ANDROID_NS, "android.permission.INTERNET"]))
            archive.writestr("classes.dex", dex(["Lkotlin/Unit;", *dex_strings]))
            archive.writestr("resources.arsc", resource_table(["res/raw/help.md", *arsc_strings], utf8))
            for name, data in (files or {}).items():
                archive.writestr(name, data)
        return path

    def run_tool(self, apk, *extra):
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = apk_hosts.main([apk, "--repo", self.dir.name, "--json", *extra])
        rows = json.loads(out.getvalue()) if out.getvalue().strip() else []
        return code, rows, err.getvalue()

    def outside(self, rows):
        return sorted({row["host"] for row in rows if row["class"] == "FUERA DE LA LISTA"})

    def test_aCleanApkPasses(self):
        apk = self.build(
            dex_strings=["https://app.koofr.net/dav/Koofr", "https://dav.example.com/files"],
            arsc_strings=["https://support.google.com/accounts/answer/1"],
            files={
                "res/raw/help.md": b"Ver https://www.example.org/ayuda",
                "assets/licenses/ofl.txt": b"https://scripts.sil.org/OFL",
            },
        )
        code, rows, _ = self.run_tool(apk)
        self.assertEqual(0, code)
        classes = {row["host"]: row["class"] for row in rows}
        self.assertEqual("DESTINO", classes["app.koofr.net"])
        self.assertEqual("ENLACE", classes["support.google.com"])
        self.assertEqual("EXCLUIDO", classes["schemas.android.com"])
        self.assertEqual("EXCLUIDO", classes["dav.example.com"])
        self.assertEqual("EXCLUIDO", classes["scripts.sil.org"])

    def test_anUnknownHostInTheCodeFails(self):
        apk = self.build(dex_strings=["https://telemetry.unlisted-service.net/v1/collect"])
        code, rows, err = self.run_tool(apk)
        self.assertEqual(1, code)
        self.assertEqual(["telemetry.unlisted-service.net"], self.outside(rows))
        self.assertIn("telemetry.unlisted-service.net", err)

    def test_aKnownExclusionOutsideItsFilesFails(self):
        # Un aviso de licencia puede nombrar github.com; el código, no.
        apk = self.build(dex_strings=["https://github.com/someone/something/releases"])
        code, rows, _ = self.run_tool(apk)
        self.assertEqual(1, code)
        self.assertEqual(["github.com"], self.outside(rows))

    def test_theUpdateHostPassesOnlyWhenItIsTheConfiguredOne(self):
        apk = self.build(arsc_strings=["https://usuario.github.io/app/version.json"])
        self.assertEqual(1, self.run_tool(apk)[0])
        code, rows, _ = self.run_tool(apk, "--manifest-url", "https://usuario.github.io/app/version.json")
        self.assertEqual(0, code)
        self.assertEqual("DESTINO", {row["host"]: row["class"] for row in rows}["usuario.github.io"])
        self.assertEqual(1, self.run_tool(apk, "--manifest-url", "https://otro.github.io/version.json")[0])

    def test_theUpdateHostComesFromTheRepositoryStrings(self):
        values = os.path.join(self.dir.name, "app", "src", "main", "res", "values")
        os.makedirs(values)
        with open(os.path.join(values, "update_strings.xml"), "w", encoding="utf-8") as handle:
            handle.write(
                '<resources><string name="update_manifest_url" translatable="false">'
                "https://usuario.github.io/app/version.json</string></resources>"
            )
        apk = self.build(arsc_strings=["https://usuario.github.io/app/version.json"])
        self.assertEqual(0, self.run_tool(apk)[0])

    def test_theApkHostsPassOnlyWithTheReleaseTemplate(self):
        apk = self.build(
            dex_strings=[
                "github.com",
                "release-assets.githubusercontent.com",
                "objects.githubusercontent.com",
            ],
            arsc_strings=["https://github.com/usuario/app/releases/download/v%1$s/app-%1$s.apk"],
        )
        # Sin plantilla, ninguno de los tres pasa: no hay descarga que los use.
        code, rows, _ = self.run_tool(apk)
        self.assertEqual(1, code)
        self.assertEqual(
            ["github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"],
            self.outside(rows),
        )
        template = "https://github.com/usuario/app/releases/download/v%1$s/app-%1$s.apk"
        code, rows, _ = self.run_tool(apk, "--apk-url-template", template)
        self.assertEqual(0, code)
        classes = {row["host"]: row["class"] for row in rows}
        self.assertEqual("DESTINO", classes["github.com"])
        self.assertEqual("DESTINO", classes["release-assets.githubusercontent.com"])
        self.assertEqual("DESTINO", classes["objects.githubusercontent.com"])

    def test_theApkTemplateComesFromTheRepositoryStrings(self):
        values = os.path.join(self.dir.name, "app", "src", "main", "res", "values")
        os.makedirs(values)
        with open(os.path.join(values, "update_strings.xml"), "w", encoding="utf-8") as handle:
            handle.write(
                '<resources><string name="update_apk_url_template" translatable="false">'
                "https://github.com/usuario/app/releases/download/v%1$s/app-%1$s.apk</string></resources>"
            )
        apk = self.build(dex_strings=["release-assets.githubusercontent.com"])
        self.assertEqual(0, self.run_tool(apk)[0])

    def test_anotherFileServerIsNotAllowedByTheTemplate(self):
        apk = self.build(dex_strings=["https://files.unlisted-service.net/apk"])
        template = "https://github.com/usuario/app/releases/download/v%1$s/app-%1$s.apk"
        code, rows, _ = self.run_tool(apk, "--apk-url-template", template)
        self.assertEqual(1, code)
        self.assertEqual(["files.unlisted-service.net"], self.outside(rows))

    def test_aPlainHttpApkTemplateIsRejected(self):
        apk = self.build()
        template = "http://github.com/usuario/app/releases/download/v%1$s/app-%1$s.apk"
        self.assertEqual(2, self.run_tool(apk, "--apk-url-template", template)[0])

    def test_aPlainHttpUpdateAddressIsRejected(self):
        apk = self.build()
        self.assertEqual(2, self.run_tool(apk, "--manifest-url", "http://usuario.github.io/version.json")[0])

    def test_anAddressBuiltAtRunTimeFails(self):
        apk = self.build(dex_strings=["https://%s/upload"])
        code, rows, _ = self.run_tool(apk)
        self.assertEqual(1, code)
        self.assertEqual(["https://%s"], self.outside(rows))

    def test_hostsInAUtf16ResourceTableAreFound(self):
        apk = self.build(arsc_strings=["Abrir https://telemetry.unlisted-service.net/"], utf8=False)
        self.assertEqual(["telemetry.unlisted-service.net"], self.outside(self.run_tool(apk)[1]))

    def test_aBareHostNameWithoutSchemeCounts(self):
        apk = self.build(dex_strings=["telemetry.unlisted-service.net"])
        self.assertEqual(["telemetry.unlisted-service.net"], self.outside(self.run_tool(apk)[1]))

    def test_packageNamesAndSqlColumnsAreNotHosts(self):
        apk = self.build(
            dex_strings=[
                "io.github.someone.shop.app",
                "com.google.crypto.tink.aead",
                "SELECT l.net FROM sale_lines AS l",
                "androidx.work.impl.background",
            ],
        )
        code, rows, _ = self.run_tool(apk)
        self.assertEqual(0, code)
        self.assertEqual([], self.outside(rows))

    def test_anEmailInANativeLibraryCounts(self):
        apk = self.build(files={"lib/arm64-v8a/libfoo.so": b"\x00\x01someone@sub.example\x00\x7fU@UcULU.U\x00"})
        self.assertEqual(["sub.example"], self.outside(self.run_tool(apk)[1]))

    def test_thePublicSuffixListIsSkippedWhole(self):
        apk = self.build(files={"assets/PublicSuffixDatabase.list": b"com\ngithub.io\nunlisted-service.net\n"})
        code, rows, _ = self.run_tool(apk)
        self.assertEqual(0, code)
        self.assertIn("(archivo entero)", {row["host"] for row in rows})

    def test_aZipWithoutCodeIsNotAnApk(self):
        path = os.path.join(self.dir.name, "not.apk")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("hello.txt", b"https://telemetry.unlisted-service.net/")
        self.assertEqual(2, self.run_tool(path)[0])

    def test_aBrokenDexIsAnErrorNotAPass(self):
        path = os.path.join(self.dir.name, "broken.apk")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", binary_xml([ANDROID_NS]))
            archive.writestr("classes.dex", b"dex\n035\x00" + b"\xff" * 0x70)
        self.assertEqual(2, self.run_tool(path)[0])


if __name__ == "__main__":
    unittest.main()
