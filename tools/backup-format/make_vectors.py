# -*- coding: utf-8 -*-
"""Genera los vectores de prueba del formato 2 con Tink para Python: vectors/format-v2-vectors.json.

    python make_vectors.py

Todo es inventado: dos códigos sintéticos publicados aquí, bases SQLite pequeñas con filas de ejemplo y una foto falsa.
Ninguna clave real. El cifrado usa un Salt y un NoncePrefix aleatorios, así que cada corrida da archivos distintos: el
JSON versionado es la referencia, y solo se regenera si cambia el formato. Al terminar, abre cada copia con rescue.py y
comprueba cada resultado esperado, también las alteraciones.
"""
import base64
import gzip
import hashlib
import io
import json
import os
import sqlite3
import struct
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rescue  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUTPUT = os.path.join(HERE, "vectors", "format-v2-vectors.json")

# Códigos sintéticos. El primero es el que publica la especificación. Nunca se usan para una copia real.
CODE_A = "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF51"
CODE_B = rescue.printable("0123456789ABCDEFGHJKMNPQRS")

CREATED_AT_UTC_MS = 1_793_491_200_000  # 2026-11-01T00:00:00Z
DEVICE_ID = bytes.fromhex("6b1f3c2a9d4e4f0a8c7b5e6d4c3b2a19")
UNKNOWN_EXTENSION_TAG = 0x7F01


def sample_database(rows, schema_version):
    """Una base SQLite pequeña con filas inventadas. Devuelve sus bytes."""
    folder = tempfile.mkdtemp(prefix="slbk-vectors-")
    path = os.path.join(folder, "sample.db")
    try:
        connection = sqlite3.connect(path)
        connection.execute("CREATE TABLE sample_items (id INTEGER PRIMARY KEY, name TEXT NOT NULL, amount INTEGER NOT NULL)")
        connection.executemany("INSERT INTO sample_items (name, amount) VALUES (?, ?)",
                               [(f"sample-item-{i}", 100 + i * 37) for i in range(1, rows + 1)])
        connection.execute(f"PRAGMA user_version = {schema_version}")
        connection.commit()
        connection.execute("VACUUM")
        connection.close()
        with open(path, "rb") as file:
            return file.read()
    finally:
        for name in os.listdir(folder):
            os.remove(os.path.join(folder, name))
        os.rmdir(folder)


def fake_photo():
    """Bytes con forma de WebP, inventados: la herramienta no interpreta la imagen."""
    body = b"VP8 " + struct.pack("<I", 64) + bytes(range(64))
    return b"RIFF" + struct.pack("<I", 4 + len(body)) + b"WEBP" + body


def flip_gzip_trailer(compressed, from_end):
    """Cambia un bit del final de gzip: CRC-32 a 8 bytes del final, y el largo a 4."""
    changed = bytearray(compressed)
    changed[len(changed) - from_end] ^= 0x01
    return bytes(changed)


def header(key_id, generation, schema_version, raw_size, extensions=b"", header_length=None):
    length = header_length if header_length is not None else rescue.FIXED_HEADER_LENGTH + len(extensions)
    fixed = (rescue.MAGIC + struct.pack(">HHBBBB", rescue.FORMAT_VERSION, length, rescue.AEAD_ID, rescue.KEY_SOURCE,
                                        rescue.PAYLOAD_CODEC, 0)
             + key_id + struct.pack(">QIq", generation, schema_version, CREATED_AT_UTC_MS) + DEVICE_ID
             + struct.pack(">Q", raw_size))
    assert len(fixed) == rescue.FIXED_HEADER_LENGTH
    return fixed + extensions


class KeepOpen(io.RawIOBase):
    """Recibe lo que escribe Tink sin que cerrar el flujo cifrado cierre el búfer."""

    def __init__(self, target):
        self.target = target

    def writable(self):
        return True

    def write(self, data):
        return self.target.write(data)


def encrypt(printed, header_bytes, payload):
    """La cabecera en claro y, detrás, el flujo de Tink con la cabecera completa como datos asociados."""
    backup_key, _ = rescue.derive(rescue.parse_code(printed))
    buffer = io.BytesIO()
    buffer.write(header_bytes)
    with rescue.streaming_aead_for(backup_key).new_encrypting_stream(KeepOpen(buffer), header_bytes) as stream:
        stream.write(payload)
    return buffer.getvalue()


def key_id_of(printed):
    return rescue.derive(rescue.parse_code(printed))[1]


def copy_entry(name, kind, printed, data, expected, description, plain=None):
    entry = {
        "name": name,
        "description": description,
        "kind": kind,
        "code": printed,
        "file_sha256": hashlib.sha256(data).hexdigest(),
        "file_bytes": len(data),
        "file_base64": base64.b64encode(data).decode("ascii"),
        "expected": expected,
    }
    if plain is not None:
        entry["plain_sha256"] = hashlib.sha256(plain).hexdigest()
        entry["plain_bytes"] = len(plain)
    return entry


def build():
    key_a, key_b = key_id_of(CODE_A), key_id_of(CODE_B)
    database = sample_database(rows=120, schema_version=1)
    other_database = sample_database(rows=80, schema_version=1)
    photo = fake_photo()
    gz = lambda raw: gzip.compress(raw, 6, mtime=0)  # noqa: E731
    extension = struct.pack(">HH", UNKNOWN_EXTENSION_TAG, 6) + b"future"

    copies = [
        copy_entry("database-basic", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database)), gz(database)), "OK",
                   "Base pequeña, cabecera de 68 bytes, un solo segmento.", database),
        copy_entry("database-other-generation", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 8, 1, len(other_database)), gz(other_database)), "OK",
                   "Otra copia con el mismo código y la cabecera del mismo largo: sirve para mezclar cabeceras.",
                   other_database),
        copy_entry("database-unknown-extension", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 9, 1, len(database), extension), gz(database)), "OK",
                   "Cabecera de 78 bytes con una extensión desconocida: se ignora, pero va autenticada.", database),
        copy_entry("database-second-code", "database", CODE_B,
                   encrypt(CODE_B, header(key_b, 3, 1, len(database)), gz(database)), "OK",
                   "La misma base con el segundo código sintético.", database),
        copy_entry("media-basic", "media", CODE_A,
                   encrypt(CODE_A, header(key_a, 0, 0, len(photo)), gz(photo)), "OK",
                   "Foto .slmd: generation y schema_version en 0, y raw_size es el tamaño de la foto.", photo),
        copy_entry("database-raw-size-lies", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database) + 1), gz(database)), "SIZE_MISMATCH",
                   "raw_size dice un byte más que el contenido: autenticado, pero no coincide."),
        copy_entry("database-not-sqlite", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(photo)), gz(photo)), "NOT_SQLITE",
                   "Bien cifrada, pero el contenido no empieza con el encabezado de SQLite."),
        copy_entry("database-data-after-gzip", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database)), gz(database) + b"\x00extra"), "DAMAGED",
                   "Bytes después del final de gzip, dentro del cifrado."),
        copy_entry("database-truncated-gzip", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database)), gz(database)[:-12]), "DAMAGED",
                   "gzip sin su final, dentro del cifrado."),
        copy_entry("database-two-gzip-members", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database) * 2), gz(database) + gz(database)), "DAMAGED",
                   "Dos miembros de gzip seguidos: la app escribe uno solo."),
        copy_entry("database-gzip-crc-wrong", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database)), flip_gzip_trailer(gz(database), 8)), "DAMAGED",
                   "El CRC-32 del final de gzip no coincide con el contenido."),
        copy_entry("database-gzip-size-wrong", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database)), flip_gzip_trailer(gz(database), 4)), "DAMAGED",
                   "El largo del final de gzip no coincide con el contenido."),
        copy_entry("header-loose-bytes", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database), b"\x00\x01"), gz(database)),
                   "HEADER_OUT_OF_LIST",
                   "Cabecera de 70 bytes con 2 bytes que no forman una extensión: se rechaza antes de usar la clave."),
        copy_entry("header-extension-overflows", "database", CODE_A,
                   encrypt(CODE_A, header(key_a, 7, 1, len(database), struct.pack(">HH", 1, 9) + b"abcd"), gz(database)),
                   "HEADER_OUT_OF_LIST",
                   "Una extensión dice 9 bytes y solo quedan 4 en la cabecera."),
        copy_entry("plain-format-one", "database", CODE_A, gz(database), "PLAIN_FORMAT",
                   "Una copia en claro (.db.gz): empieza con 1F 8B.", None),
        copy_entry("empty-file", "database", CODE_A, b"", "NOT_A_BACKUP", "Archivo vacío."),
    ]

    base = "database-basic"
    flip = lambda offset, mask=0x01: {"op": "xor", "offset": offset, "mask": mask}  # noqa: E731
    set_u16 = lambda offset, value: {"op": "set", "offset": offset, "hex": struct.pack(">H", value).hex()}  # noqa: E731
    set_byte = lambda offset, value: {"op": "set", "offset": offset, "hex": bytes([value]).hex()}  # noqa: E731
    size = next(c for c in copies if c["name"] == base)["file_bytes"]
    mutations = [
        ("magic-changed", flip(0), "NOT_A_BACKUP"),
        ("format-version-newer", set_u16(8, 3), "NEWER_FORMAT"),
        ("format-version-older", set_u16(8, 1), "HEADER_OUT_OF_LIST"),
        ("header-len-too-short", set_u16(10, 67), "HEADER_OUT_OF_LIST"),
        ("header-len-too-long", set_u16(10, 4097), "HEADER_OUT_OF_LIST"),
        ("aead-id-unknown", set_byte(12, 2), "HEADER_OUT_OF_LIST"),
        ("key-source-unknown", set_byte(13, 2), "HEADER_OUT_OF_LIST"),
        ("payload-codec-unknown", set_byte(14, 2), "HEADER_OUT_OF_LIST"),
        ("reserved-not-zero", set_byte(15, 1), "HEADER_OUT_OF_LIST"),
        ("raw-size-over-limit", {"op": "set", "offset": 60, "hex": struct.pack(">Q", (1 << 30) + 1).hex()}, "TOO_LARGE"),
        ("key-id-changed", flip(16), "OTHER_CODE"),
        ("generation-changed", flip(31), "DAMAGED"),
        ("schema-version-changed", flip(35), "DAMAGED"),
        ("created-at-changed", flip(43), "DAMAGED"),
        ("device-id-changed", flip(44), "DAMAGED"),
        ("raw-size-changed", flip(67), "DAMAGED"),
        ("tink-header-length-changed", flip(68), "DAMAGED"),
        ("tink-salt-changed", flip(69), "DAMAGED"),
        ("tink-nonce-prefix-changed", flip(68 + 1 + 32), "DAMAGED"),
        ("ciphertext-changed", flip(68 + 40 + 5), "DAMAGED"),
        ("tag-changed", flip(size - 1, 0x80), "DAMAGED"),
        ("last-byte-removed", {"op": "truncate", "length": size - 1}, "DAMAGED"),
        ("only-header", {"op": "truncate", "length": 68}, "DAMAGED"),
        ("header-cut", {"op": "truncate", "length": 40}, "DAMAGED"),
        ("byte-appended", {"op": "append", "hex": "00"}, "DAMAGED"),
        ("header-from-other-copy", {"op": "header_from", "copy": "database-other-generation", "length": 68}, "DAMAGED"),
        ("body-from-other-copy", {"op": "body_from", "copy": "database-second-code", "length": 68}, "DAMAGED"),
    ]
    mutation_entries = [{"name": n, "base": base, "code": CODE_A, "operation": o, "expected": e} for n, o, e in mutations]
    mutation_entries.append({"name": "valid-code-of-another-copy", "base": base, "code": CODE_B,
                             "operation": {"op": "none"}, "expected": "OTHER_CODE"})

    codes = []
    for printed in (CODE_A, CODE_B):
        canonical = rescue.parse_code(printed)
        backup_key, key_id = rescue.derive(canonical)
        codes.append({"printed": printed, "canonical": canonical, "check": rescue.check_characters(canonical),
                      "key_id": key_id.hex(), "bk_sha256": hashlib.sha256(backup_key).hexdigest()})
    typed = [
        CODE_A, CODE_A.lower(), CODE_A.replace("-", " "), CODE_A.replace("-", ""),
        "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF5I", "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF5l",
        CODE_B.replace("0", "O"), CODE_B.replace("1", "l").replace("0", "o"),
        "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF52", "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-FE51",
        "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF5", "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF511",
        "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF5U", "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF5*", "",
    ]
    typed_entries = []
    for text in typed:
        reason, canonical = rescue.classify_code(text)
        typed_entries.append({"typed": text, "result": reason, "canonical": canonical})

    return {
        "format": "SLBK",
        "format_version": 2,
        "description": ("Vectores del formato 2 de las copias cifradas. Todo es inventado y los códigos son sintéticos. "
                        "La especificación está en README.md, junto a esta carpeta."),
        "parameters": {
            "salt": rescue.SALT.decode("ascii"),
            "info_backup_key": rescue.BACKUP_KEY_INFO.decode("ascii"),
            "info_key_id": rescue.KEY_ID_INFO.decode("ascii"),
            "ciphertext_segment_size": rescue.CIPHERTEXT_SEGMENT_BYTES,
            "max_decompressed_bytes": rescue.MAX_DECOMPRESSED_BYTES,
            "created_at_utc_ms": CREATED_AT_UTC_MS,
            "device_id": DEVICE_ID.hex(),
        },
        "codes": codes,
        "typed_codes": typed_entries,
        "copies": copies,
        "mutations": mutation_entries,
    }


def apply(operation, data, copies):
    """Aplica una alteración del JSON a los bytes de una copia. La misma lógica la usa test_rescue.py."""
    op = operation["op"]
    if op == "none":
        return data
    if op == "xor":
        changed = bytearray(data)
        changed[operation["offset"]] ^= operation["mask"]
        return bytes(changed)
    if op == "set":
        value = bytes.fromhex(operation["hex"])
        offset = operation["offset"]
        return data[:offset] + value + data[offset + len(value):]
    if op == "truncate":
        return data[:operation["length"]]
    if op == "append":
        return data + bytes.fromhex(operation["hex"])
    other = base64.b64decode(copies[operation["copy"]]["file_base64"])
    length = operation["length"]
    if op == "header_from":
        return other[:length] + data[length:]
    if op == "body_from":
        return data[:length] + other[length:]
    raise ValueError(op)


def outcome(data, printed, media):
    """Abre [data] con rescue.py en memoria. Devuelve (resultado, SHA-256 del contenido o None)."""
    sink = io.BytesIO()
    try:
        canonical = rescue.parse_code(printed)
        _, _, sha256 = rescue.rescue(io.BytesIO(data), canonical, sink, media)
    except rescue.Rejected as rejected:
        return rejected.reason, None
    return "OK", sha256


def check(document):
    copies = {entry["name"]: entry for entry in document["copies"]}
    for entry in document["copies"]:
        result, sha256 = outcome(base64.b64decode(entry["file_base64"]), entry["code"], entry["kind"] == "media")
        assert result == entry["expected"], (entry["name"], result)
        if result == "OK":
            assert sha256 == entry["plain_sha256"], entry["name"]
    for entry in document["mutations"]:
        base = copies[entry["base"]]
        data = apply(entry["operation"], base64.b64decode(base["file_base64"]), copies)
        result, _ = outcome(data, entry["code"], base["kind"] == "media")
        assert result == entry["expected"], (entry["name"], result)


def main():
    document = build()
    check(document)
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as file:
        json.dump(document, file, indent=2, ensure_ascii=False)
        file.write("\n")
    print(f"{len(document['copies'])} copias y {len(document['mutations'])} alteraciones en {OUTPUT}")


if __name__ == "__main__":
    main()
