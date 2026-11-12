# -*- coding: utf-8 -*-
"""Herramienta de rescate: abre en un PC una copia cifrada (.slbk) o una foto cifrada (.slmd), solo con el código.

    python rescue.py copia.slbk --out base.db
    python rescue.py foto.slmd --out foto.webp

Pide el código de recuperación sin mostrarlo en pantalla. Si la entrada estándar no es una consola, lee una sola línea de
ella, para poder usarla desde otro programa. El código nunca se acepta como argumento, para que no quede en el historial de
la consola, y nunca se escribe en pantalla, en un archivo ni en un registro.

Pasos, en este orden (README.md de esta carpeta, sección "Orden de lectura"):

1. Comprueba los 2 caracteres de control del código, antes de abrir la copia.
2. Lee la cabecera y la valida contra la lista cerrada, antes de usar ninguna clave.
3. Compara el key_id del código con el de la cabecera.
4. Descifra en flujo con Tink oficial (AesGcmHkdfStreaming), con la cabecera completa como datos asociados.
5. Descomprime gzip con un tope de salida, y comprueba raw_size y, en una base, el encabezado de SQLite.

Escribe en un temporal junto a la salida, lo sincroniza con el disco y solo entonces lo renombra. Nunca reemplaza un archivo
que ya exista. Si algo falla, borra el temporal y no deja nada.

Requisitos: Python 3.9 o superior y la biblioteca Tink oficial (pip install tink==1.16.1, la versión probada).
"""
import argparse
import getpass
import hashlib
import hmac
import io
import locale
import os
import struct
import sys
import tempfile
import zlib

# Alfabeto Crockford Base32: sin I, L, O ni U, para que el código no forme palabras ni se confunda al copiarlo a mano.
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
CODE_LENGTH = 26
CHECK_LENGTH = 2
GROUP_LENGTH = 4
SALT = b"SLBK-v2"
BACKUP_KEY_INFO = b"backup-aead"
KEY_ID_INFO = b"key-id"
BACKUP_KEY_BYTES = 32
KEY_ID_BYTES = 8

MAGIC = bytes([0x53, 0x4C, 0x42, 0x4B, 0x0D, 0x0A, 0x1A, 0x0A])
GZIP_MAGIC = b"\x1f\x8b"
FORMAT_VERSION = 2
FIXED_HEADER_LENGTH = 68
MAX_HEADER_LENGTH = 4096
AEAD_ID = 1
KEY_SOURCE = 1
PAYLOAD_CODEC = 1
CIPHERTEXT_SEGMENT_BYTES = 1 << 20
# Tope de lo descomprimido: la app no crea una copia más grande, y así una bomba de gzip no llena el disco.
MAX_DECOMPRESSED_BYTES = 1 << 30
SQLITE_MAGIC = b"SQLite format 3\x00"
CHUNK_BYTES = 64 * 1024

# Un código de salida por motivo, para que otro programa distinga los casos.
EXIT_CODES = {
    "OK": 0,
    "CODE_INVALID": 2,
    "NOT_A_BACKUP": 3,
    "NEWER_FORMAT": 4,
    "HEADER_OUT_OF_LIST": 5,
    "OTHER_CODE": 6,
    "DAMAGED": 7,
    "SIZE_MISMATCH": 8,
    "TOO_LARGE": 9,
    "NOT_SQLITE": 10,
    "PLAIN_FORMAT": 11,
    "OUTPUT_EXISTS": 12,
    "UNREADABLE": 13,
}

# Textos para la persona, en los tres idiomas de la app. El idioma sale de --lang o del sistema, y si no se reconoce, inglés.
MESSAGES = {
    "es": {
        "prompt": "Código de recuperación (no se muestra): ",
        "ok": "Listo: {bytes} bytes, generación {generation}, creada {created} (UTC). SHA-256: {sha256}",
        "CODE_INVALID": "El código no es válido. Revisa cada grupo de 4 caracteres. No se escribió nada.",
        "NOT_A_BACKUP": "Este archivo no es una copia.",
        "NEWER_FORMAT": "Esta copia usa un formato más nuevo. Busca una versión nueva de esta herramienta.",
        "HEADER_OUT_OF_LIST": "La cabecera tiene valores que este formato no admite. No se puede abrir.",
        "OTHER_CODE": "Esta copia usa otro código. Prueba con el código que tenías cuando se hizo.",
        "DAMAGED": "Esta copia está dañada y no se puede abrir.",
        "SIZE_MISMATCH": "El tamaño descifrado no coincide con el de la cabecera. No se puede usar.",
        "TOO_LARGE": "La copia supera el tamaño máximo que se abre.",
        "NOT_SQLITE": "El contenido no es una base de datos. Si es una foto, usa --media.",
        "PLAIN_FORMAT": "Esta copia no está cifrada (.db.gz). Se abre con cualquier programa de gzip, sin esta herramienta.",
        "OUTPUT_EXISTS": "El archivo de salida ya existe. Elige otro nombre: esta herramienta nunca reemplaza un archivo.",
        "UNREADABLE": "No se pudo leer la copia o escribir la salida. Revisa la ruta y el espacio libre.",
    },
    "en": {
        "prompt": "Recovery code (hidden): ",
        "ok": "Done: {bytes} bytes, generation {generation}, created {created} (UTC). SHA-256: {sha256}",
        "CODE_INVALID": "The code is not valid. Check each group of 4 characters. Nothing was written.",
        "NOT_A_BACKUP": "This file is not a backup.",
        "NEWER_FORMAT": "This backup uses a newer format. Look for a new version of this tool.",
        "HEADER_OUT_OF_LIST": "The header has values this format does not allow. It cannot be opened.",
        "OTHER_CODE": "This backup uses another code. Try the code you had when it was made.",
        "DAMAGED": "This backup is damaged and cannot be opened.",
        "SIZE_MISMATCH": "The decrypted size does not match the header. It cannot be used.",
        "TOO_LARGE": "The backup is larger than the maximum this tool opens.",
        "NOT_SQLITE": "The content is not a database. If it is a photo, use --media.",
        "PLAIN_FORMAT": "This backup is not encrypted (.db.gz). Any gzip program opens it, without this tool.",
        "OUTPUT_EXISTS": "The output file already exists. Choose another name: this tool never replaces a file.",
        "UNREADABLE": "Could not read the backup or write the output. Check the path and the free space.",
    },
    "pt": {
        "prompt": "Código de recuperação (não é exibido): ",
        "ok": "Pronto: {bytes} bytes, geração {generation}, criada {created} (UTC). SHA-256: {sha256}",
        "CODE_INVALID": "O código não é válido. Confira cada grupo de 4 caracteres. Nada foi gravado.",
        "NOT_A_BACKUP": "Este arquivo não é um backup.",
        "NEWER_FORMAT": "Este backup usa um formato mais novo. Procure uma versão nova desta ferramenta.",
        "HEADER_OUT_OF_LIST": "O cabeçalho tem valores que este formato não aceita. Não é possível abrir.",
        "OTHER_CODE": "Este backup usa outro código. Tente o código que você tinha quando ele foi feito.",
        "DAMAGED": "Este backup está danificado e não pode ser aberto.",
        "SIZE_MISMATCH": "O tamanho descriptografado não coincide com o do cabeçalho. Não pode ser usado.",
        "TOO_LARGE": "O backup passa do tamanho máximo que esta ferramenta abre.",
        "NOT_SQLITE": "O conteúdo não é um banco de dados. Se for uma foto, use --media.",
        "PLAIN_FORMAT": "Este backup não está criptografado (.db.gz). Qualquer programa de gzip o abre, sem esta ferramenta.",
        "OUTPUT_EXISTS": "O arquivo de saída já existe. Escolha outro nome: esta ferramenta nunca substitui um arquivo.",
        "UNREADABLE": "Não foi possível ler o backup ou gravar a saída. Confira o caminho e o espaço livre.",
    },
}


class Rejected(Exception):
    """Un motivo de rechazo de la lista de EXIT_CODES. Nunca lleva el código ni datos de la copia."""

    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


def hkdf_sha256(ikm, salt, info, length):
    """HKDF con HMAC-SHA256, según RFC 5869: extraer y expandir."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    output, block, counter = b"", b"", 1
    while len(output) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        output += block
        counter += 1
    return output[:length]


def check_characters(canonical):
    """Los primeros 10 bits de SHA-256 de los 26 caracteres canónicos, en 2 caracteres Crockford."""
    digest = hashlib.sha256(canonical.encode("ascii")).digest()
    first = digest[0] >> 3
    second = ((digest[0] & 0x07) << 2) | (digest[1] >> 6)
    return ALPHABET[first] + ALPHABET[second]


def normalize(typed):
    """Como la app: mayúsculas, sin guiones ni espacios, I y L pasan a 1, y O pasa a 0. Nada más se quita."""
    replacements = {"I": "1", "L": "1", "O": "0"}
    return "".join(replacements.get(char, char) for char in typed.upper() if char not in "- ")


def classify_code(typed):
    """Devuelve (motivo, canónico). El motivo es VALID, WRONG_LENGTH, INVALID_CHARACTER o CHECK_MISMATCH."""
    normalized = normalize(typed)
    if len(normalized) != CODE_LENGTH + CHECK_LENGTH:
        return "WRONG_LENGTH", None
    if any(char not in ALPHABET for char in normalized):
        return "INVALID_CHARACTER", None
    canonical, check = normalized[:CODE_LENGTH], normalized[CODE_LENGTH:]
    if check_characters(canonical) != check:
        return "CHECK_MISMATCH", None
    return "VALID", canonical


def parse_code(typed):
    """Los 26 caracteres canónicos de un código escrito, o Rejected(CODE_INVALID) sin usarlo para nada."""
    reason, canonical = classify_code(typed)
    if reason != "VALID":
        raise Rejected("CODE_INVALID")
    return canonical


def printable(canonical):
    """El código como se imprime: 26 caracteres y 2 de control, en 7 grupos de 4 separados por guiones."""
    full = canonical + check_characters(canonical)
    return "-".join(full[i:i + GROUP_LENGTH] for i in range(0, len(full), GROUP_LENGTH))


def derive(canonical):
    """Devuelve (BK, key_id). BK es la clave de las copias, y key_id dice qué código abre una copia sin revelarlo."""
    ikm = canonical.encode("ascii")
    return (hkdf_sha256(ikm, SALT, BACKUP_KEY_INFO, BACKUP_KEY_BYTES),
            hkdf_sha256(ikm, SALT, KEY_ID_INFO, KEY_ID_BYTES))


def read_exact(stream, length):
    data = b""
    while len(data) < length:
        chunk = stream.read(length - len(data))
        if not chunk:
            break
        data += chunk
    return data


def parse_header(stream):
    """Lee y valida la cabecera contra la lista cerrada, antes de usar ninguna clave. Devuelve (bytes, campos)."""
    prefix = read_exact(stream, 12)
    if prefix[:2] == GZIP_MAGIC:
        raise Rejected("PLAIN_FORMAT")
    if len(prefix) < len(MAGIC) or prefix[:len(MAGIC)] != MAGIC:
        raise Rejected("NOT_A_BACKUP")
    if len(prefix) < 12:
        raise Rejected("DAMAGED")
    version, header_length = struct.unpack(">HH", prefix[8:12])
    if version > FORMAT_VERSION:
        raise Rejected("NEWER_FORMAT")
    if version != FORMAT_VERSION:
        raise Rejected("HEADER_OUT_OF_LIST")
    if not FIXED_HEADER_LENGTH <= header_length <= MAX_HEADER_LENGTH:
        raise Rejected("HEADER_OUT_OF_LIST")
    rest = read_exact(stream, header_length - 12)
    if len(rest) < header_length - 12:
        raise Rejected("DAMAGED")
    header = prefix + rest
    for offset, expected in ((12, AEAD_ID), (13, KEY_SOURCE), (14, PAYLOAD_CODEC), (15, 0)):
        if header[offset] != expected:
            raise Rejected("HEADER_OUT_OF_LIST")
    generation, schema_version, created_at, device_id, raw_size = struct.unpack(">QIq16sQ", header[24:68])
    # u64 con el bit alto encendido: ninguna copia real llega ahí, y la app no lo representa.
    if generation >= 1 << 63 or raw_size >= 1 << 63:
        raise Rejected("HEADER_OUT_OF_LIST")
    position = FIXED_HEADER_LENGTH
    extensions = []
    # Extensiones TLV: lo desconocido se ignora, pero deben llenar la cabecera exacta, sin bytes sueltos.
    while position < header_length:
        if header_length - position < 4:
            raise Rejected("HEADER_OUT_OF_LIST")
        tag, length = struct.unpack(">HH", header[position:position + 4])
        if position + 4 + length > header_length:
            raise Rejected("HEADER_OUT_OF_LIST")
        extensions.append(tag)
        position += 4 + length
    if raw_size > MAX_DECOMPRESSED_BYTES:
        raise Rejected("TOO_LARGE")
    fields = {
        "format_version": version,
        "header_len": header_length,
        "key_id": header[16:24].hex(),
        "generation": generation,
        "schema_version": schema_version,
        "created_at_utc_ms": created_at,
        "device_id": device_id.hex(),
        "raw_size": raw_size,
        "extension_tags": extensions,
    }
    return header, fields


def streaming_aead_for(backup_key):
    """Tink para Python no recibe BK suelto: se arma en memoria un keyset de una sola clave RAW, que nunca se escribe."""
    import tink
    from tink import secret_key_access, streaming_aead
    from tink.proto import aes_gcm_hkdf_streaming_pb2, common_pb2, tink_pb2

    streaming_aead.register()
    key = aes_gcm_hkdf_streaming_pb2.AesGcmHkdfStreamingKey(
        version=0,
        key_value=backup_key,
        params=aes_gcm_hkdf_streaming_pb2.AesGcmHkdfStreamingParams(
            ciphertext_segment_size=CIPHERTEXT_SEGMENT_BYTES,
            derived_key_size=BACKUP_KEY_BYTES,
            hkdf_hash_type=common_pb2.SHA256,
        ),
    )
    keyset = tink_pb2.Keyset(
        primary_key_id=1,
        key=[
            tink_pb2.Keyset.Key(
                key_data=tink_pb2.KeyData(
                    type_url="type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey",
                    value=key.SerializeToString(),
                    key_material_type=tink_pb2.KeyData.SYMMETRIC,
                ),
                status=tink_pb2.ENABLED,
                key_id=1,
                output_prefix_type=tink_pb2.RAW,
            )
        ],
    )
    handle = tink.proto_keyset_format.parse(keyset.SerializeToString(), secret_key_access.TOKEN)
    return handle.primitive(streaming_aead.StreamingAead)


def rescue(source, canonical, sink, media=False):
    """Descifra [source], abierto al inicio, hacia [sink]. Devuelve (campos de la cabecera, bytes escritos, SHA-256)."""
    import tink

    header, fields = parse_header(source)
    backup_key, key_id = derive(canonical)
    if not hmac.compare_digest(key_id.hex(), fields["key_id"]):
        raise Rejected("OTHER_CODE")
    aead = streaming_aead_for(backup_key)
    inflater = zlib.decompressobj(wbits=31)
    digest = hashlib.sha256()
    limit = fields["raw_size"]
    state = {"total": 0, "first": b""}

    def emit(data):
        state["total"] += len(data)
        if state["total"] > limit:
            raise Rejected("SIZE_MISMATCH")
        if len(state["first"]) < len(SQLITE_MAGIC):
            state["first"] += data[:len(SQLITE_MAGIC) - len(state["first"])]
        digest.update(data)
        sink.write(data)

    try:
        with aead.new_decrypting_stream(source, header) as plain:
            while True:
                chunk = plain.read(CHUNK_BYTES)
                if not chunk:
                    break
                pending = chunk
                while pending:
                    if inflater.eof:
                        # Bytes después del final de gzip: la app escribe un solo miembro y nada más.
                        raise Rejected("DAMAGED")
                    # Salida acotada por llamada: una bomba de gzip no llena la memoria.
                    emit(inflater.decompress(pending, CHUNK_BYTES * 16))
                    pending = inflater.unconsumed_tail
                    if inflater.unused_data:
                        raise Rejected("DAMAGED")
            emit(inflater.flush())
    except tink.TinkError:
        raise Rejected("DAMAGED")
    except zlib.error:
        raise Rejected("DAMAGED")
    if not inflater.eof:
        raise Rejected("DAMAGED")
    if state["total"] != fields["raw_size"]:
        raise Rejected("SIZE_MISMATCH")
    if not media and state["first"] != SQLITE_MAGIC:
        raise Rejected("NOT_SQLITE")
    return fields, state["total"], digest.hexdigest()


def pick_language(requested):
    """El idioma de --lang, o el del sistema si es uno de los tres, o inglés."""
    candidates = [requested] if requested else []
    for variable in ("LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG"):
        candidates.append(os.environ.get(variable))
    try:
        candidates.append(locale.getlocale()[0])
    except (ValueError, TypeError):
        pass
    for candidate in candidates:
        if not candidate:
            continue
        lowered = candidate.lower()
        for language in MESSAGES:
            if lowered.startswith(language) or lowered.startswith({"es": "spanish", "en": "english", "pt": "portuguese"}[language]):
                return language
    return "en"


def read_typed_code(texts):
    if sys.stdin.isatty():
        return getpass.getpass(texts["prompt"]).strip()
    # Solo se quita lo que rodea la línea, como el salto final. Lo de adentro lo juzga la normalización.
    return sys.stdin.readline().strip()


def format_utc(epoch_ms):
    import datetime

    try:
        instant = datetime.datetime.fromtimestamp(epoch_ms / 1000, tz=datetime.timezone.utc)
    except (OverflowError, OSError, ValueError):
        return str(epoch_ms)
    return instant.strftime("%Y-%m-%d %H:%M:%S")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Open an encrypted backup (.slbk) or photo (.slmd) with its recovery code.")
    parser.add_argument("copy", help=".slbk or .slmd file")
    parser.add_argument("--out", required=True, help="output file, which must not exist: the database, or the photo")
    parser.add_argument("--media", action="store_true", help="the file is a photo (.slmd): SQLite is not required")
    parser.add_argument("--lang", choices=sorted(MESSAGES), help="language of the messages: es, en or pt")
    args = parser.parse_args(argv)
    texts = MESSAGES[pick_language(args.lang)]
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    media = args.media or args.copy.lower().endswith(".slmd")
    temporary = None
    try:
        canonical = parse_code(read_typed_code(texts))
        if os.path.lexists(args.out):
            raise Rejected("OUTPUT_EXISTS")
        directory = os.path.dirname(os.path.abspath(args.out))
        handle, temporary = tempfile.mkstemp(prefix=".rescue-", suffix=".tmp", dir=directory)
        with open(args.copy, "rb") as raw_source, os.fdopen(handle, "wb") as sink:
            fields, total, sha256 = rescue(io.BufferedReader(raw_source, CHUNK_BYTES), canonical, sink, media)
            sink.flush()
            os.fsync(sink.fileno())
        if os.path.lexists(args.out):
            raise Rejected("OUTPUT_EXISTS")
        os.replace(temporary, args.out)
        temporary = None
    except Rejected as rejected:
        print(f"{rejected.reason}: {texts[rejected.reason]}", file=sys.stderr)
        return EXIT_CODES[rejected.reason]
    except OSError:
        print(f"UNREADABLE: {texts['UNREADABLE']}", file=sys.stderr)
        return EXIT_CODES["UNREADABLE"]
    finally:
        if temporary is not None and os.path.exists(temporary):
            os.remove(temporary)
    print(texts["ok"].format(bytes=total, generation=fields["generation"],
                             created=format_utc(fields["created_at_utc_ms"]), sha256=sha256))
    return EXIT_CODES["OK"]


if __name__ == "__main__":
    sys.exit(main())
