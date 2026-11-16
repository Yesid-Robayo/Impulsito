# -*- coding: utf-8 -*-
"""Busca en un APK cada URL y cada nombre de host, y falla si aparece un destino fuera de la lista cerrada.

    python -I tools/verification/apk_hosts.py app/build/outputs/apk/release/app-release.apk
    python -I tools/verification/apk_hosts.py app-release.apk --manifest-url https://usuario.github.io/app/version.json

La app solo se conecta a dos destinos: el servicio WebDAV que escribe o elige la persona, y el aviso de
versión firmado, al tocar `Buscar actualización`. El host de WebDAV lo escribe la persona y no está en el
APK; lo que sí está son las sugerencias de la pantalla de conectar un servicio, que solo se abren si la
persona elige una. Cualquier otro host que aparezca detiene la publicación hasta que alguien lo revise y
lo agregue aquí con su razón.

Qué lee, dentro del APK (que es un ZIP):

- `classes*.dex`: la tabla de cadenas de cada archivo, entera. Ahí está cada literal del código, propio y
  de las bibliotecas.
- `resources.arsc` y cada XML compilado (`AndroidManifest.xml`, `res/**/*.xml`): sus tablas de cadenas.
- Los recursos crudos y los recursos de la app: `res/raw/**`, `assets/**` y cualquier otro archivo de
  texto, como texto.
- Las bibliotecas nativas (`lib/**/*.so`) y cualquier otro binario: las secuencias de caracteres
  imprimibles.

Qué cuenta como host: lo que va después de `esquema://` en un esquema de red, lo que va después de `@`
en un correo, y una cadena con forma de nombre de dominio con un dominio de nivel superior conocido.
Los nombres de paquete de Java y Kotlin, como `com.example.app`, no cuentan: van al revés. Una URL de
red cuyo host no es un nombre fijo, como `https://%s/`, cuenta igual, porque se arma al correr.

Cada host encontrado cae en una de estas clases, en este orden:

1. `DESTINO`: la lista cerrada de destinos de red. Pasa.
2. `ENLACE`: lo abre el navegador u otra app solo si la persona lo toca; la app no lee la respuesta. Pasa.
3. `EXCLUIDO`: espacios de nombres XML, textos de licencias, ejemplos reservados y hosts que las
   bibliotecas nombran en sus mensajes, que ningún código abre. Cada exclusión dice por qué, y vale solo
   en los archivos donde se revisó. Pasa.
4. `LOCAL`: un esquema que no es red, como `content://`. Pasa.
5. Cualquier otro: `FUERA DE LA LISTA`. Falla.

Lo que se excluye se revisó en un APK de `release`, que pasa por R8. Un APK de `debug` trae además las
bibliotecas enteras, con hosts que R8 quita, y por eso no es el que se verifica.

Salida: una tabla con cada host, su clase, cuántas veces aparece y en qué archivos. Con `--json`, lo
mismo en JSON. Código de salida: 0 si todo pasa, 1 si aparece un host fuera de la lista, 2 si el APK no
se pudo leer.

Solo usa la biblioteca estándar de Python 3.9 o superior. No abre ninguna conexión.
"""
import argparse
import fnmatch
import json
import os
import re
import struct
import sys
import zipfile
import zlib
from xml.etree import ElementTree

# --- Lista cerrada -----------------------------------------------------------------------------------

# Las sugerencias de la pantalla de conectar un servicio WebDAV, en `WEBDAV_SUGGESTIONS` de
# `data/.../destination/WebDavConnectionService.kt`. Solo entra un servicio que pasó la prueba de
# servicios en un teléfono. Una sugerencia nueva entra aquí en el mismo cambio que la agrega.
WEBDAV_SUGGESTION_HOSTS = ("app.koofr.net",)

# El host del aviso de versión sale de `update_manifest_url`, en este archivo del repositorio, salvo que se
# pase `--manifest-url`. Vacío mientras no se publiquen las páginas: entonces ningún host del aviso pasa.
UPDATE_STRINGS = os.path.join("app", "src", "main", "res", "values", "update_strings.xml")
MANIFEST_URL_KEY = "update_manifest_url"

# Lo que abre otra app solo al tocarlo, según la tabla de destinos de la guía de pruebas: la guía de la
# cuenta de la nube y la de borrado, en la ayuda y en Ajustes, y la guía de ayuda. Las páginas públicas de
# la app viven en el mismo host del aviso de versión, que se agrega solo.
LINK_HOSTS = (
    ("support.google.com", "Guía de la cuenta de la nube: el navegador la abre al tocar el enlace"),
    ("myaccount.google.com", "Guía de la cuenta de la nube: el navegador la abre al tocar el enlace"),
    ("www.google.com", "Guía de ayuda: el navegador la abre al tocar el enlace"),
)

# Exclusiones: (patrón de host, patrón de archivo dentro del APK, razón). El patrón de host admite `*.` al
# principio para un dominio con sus subdominios. El de archivo es de `fnmatch`: `*` es cualquier archivo.
# Solo entra lo que ya apareció en un APK de la app y alguien revisó, acotado a los archivos donde
# apareció: un host nuevo, o uno conocido en otro archivo, falla hasta que se revise y se anote aquí.
EXCLUSIONS = (
    # Espacios de nombres XML: identifican un vocabulario, nadie se conecta a ellos.
    ("schemas.android.com", "*", "Espacio de nombres XML de Android, en cada XML compilado y en el código"),
    ("www.w3.org", "*", "Espacios de nombres XML del W3C"),
    ("schemas.openxmlformats.org", "classes*.dex", "Espacios de nombres de la hoja de cálculo que exporta la app"),
    ("xmlpull.org", "classes*.dex", "Nombres de propiedades del analizador XmlPull, como process-namespaces"),
    # Textos de licencias y avisos empaquetados: la persona los lee, nadie los abre desde la app.
    ("www.apache.org", "assets/licenses/*", "Texto de la licencia Apache 2.0"),
    ("scripts.sil.org", "assets/licenses/*", "Texto de la licencia SIL Open Font"),
    ("openfontlicense.org", "assets/licenses/*", "Texto de la licencia SIL Open Font"),
    ("github.com", "assets/licenses/*", "Autores de las fuentes, en el texto de su licencia"),
    ("www.apache.org", "META-INF/*", "Avisos y licencias de las bibliotecas"),
    ("github.com", "META-INF/*", "Avisos de licencia de las bibliotecas"),
    ("projects.eclipse.org", "META-INF/*", "Aviso de licencia de Jakarta Inject"),
    # Ejemplos reservados (RFC 2606 y RFC 6761): los usan las vistas previas y los textos de ayuda.
    ("*.example.com", "*", "Dominio reservado para ejemplos"),
    ("*.example.net", "*", "Dominio reservado para ejemplos"),
    ("*.example.org", "*", "Dominio reservado para ejemplos"),
    ("*.invalid", "*", "Dominio reservado que nunca existe"),
    ("localhost", "*", "La propia máquina"),
    # Hosts que las bibliotecas nombran en mensajes de error. Ningún código los abre: el único cliente de
    # red es el de la capa de datos, con las direcciones de arriba. Cada uno con su biblioteca.
    ("developer.android.com", "classes*.dex", "AndroidX: enlace a la documentación en un mensaje de error"),
    ("goo.gle", "classes*.dex", "AndroidX: enlace corto a la documentación en un mensaje de error"),
    ("issuetracker.google.com", "classes*.dex", "AndroidX: enlaces a fallos conocidos en mensajes de error"),
    ("youtrack.jetbrains.com", "classes*.dex", "Kotlin: enlaces a fallos conocidos en mensajes de error"),
    ("youtrack.jetbrains.com", "kotlin/*", "Kotlin: enlace a un fallo conocido en sus metadatos"),
    # La versión del compilador que armó cada biblioteca nativa, en su sección `.comment`.
    ("android.googlesource.com", "lib/*", "Versión del compilador del NDK en la sección .comment"),
    ("github.com", "lib/*", "Versión del compilador de la SQLite empaquetada en la sección .comment"),
)

# Archivos que no se recorren: no son destinos, y recorrerlos llenaría la tabla de nombres que nadie abre.
SKIPPED_FILES = (
    # OkHttp la usa para decidir a qué dominio pertenece una cookie: sufijos como `com` o `github.io`.
    ("assets/PublicSuffixDatabase.list", "Lista pública de sufijos de dominio de OkHttp"),
    ("okhttp3/internal/publicsuffix/*", "Lista pública de sufijos de dominio de OkHttp"),
)

# Esquemas que abren una conexión de red. Los demás no son red: se cuentan aparte, sin host.
NETWORK_SCHEMES = {"http", "https", "ws", "wss", "ftp", "ftps", "sftp", "smtp", "imap", "pop3", "ldap", "ldaps", "rtsp"}

# Esquemas que nunca son red: archivos, contenidos de otra app, recursos y enlaces a otra app.
LOCAL_SCHEMES = {
    "content": "Proveedor de contenido de Android, no un host",
    "file": "Archivo del teléfono",
    "android.resource": "Recurso de una app",
    "android-app": "Enlace a otra app",
    "jar": "Recurso dentro de un archivo Java",
    "data": "Datos en línea",
    "blob": "Datos en memoria",
}

# Dominios de nivel superior que se buscan sin esquema. Los de los nombres de paquete al revés (`com.`,
# `org.`) se descartan aparte, mirando la primera etiqueta.
BARE_TLDS = (
    "com", "net", "org", "io", "dev", "app", "co", "me", "info", "page", "site", "cloud", "online", "gle",
    "edu", "gov", "eu", "de", "fr", "es", "br", "pt", "uk", "us", "ar", "mx", "cl", "pe", "in", "jp", "cn",
    "ru", "nl", "be", "ch", "it", "pl", "se", "no", "fi", "dk", "at", "ca", "au", "nz", "za", "kr", "tv",
)

# La primera etiqueta de un nombre de paquete al revés, o de una propiedad de sistema.
PACKAGE_ROOTS = {
    "java", "javax", "jdk", "sun", "kotlin", "kotlinx", "android", "androidx", "dalvik", "com", "org", "net",
    "io", "dev", "app", "okhttp3", "okio", "libcore", "junit", "me", "co", "de", "fr", "es", "br", "pt",
}

URL_RE = re.compile(r"([A-Za-z][A-Za-z0-9+.\-]{0,30})://([^\s\"'<>\\`{}|^]*)")
# Un correo con un dominio de nivel superior de letras: en un binario, `U@UcULU.U` no es un correo.
EMAIL_RE = re.compile(
    r"[A-Za-z0-9._%+\-]+@((?:[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?\.)+[A-Za-z]{2,24})(?![A-Za-z0-9])",
)
BARE_RE = re.compile(
    r"(?<![A-Za-z0-9_.\-/@:])"
    r"((?:[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?\.)+(?:" + "|".join(BARE_TLDS) + r"))"
    r"(?![A-Za-z0-9_\-]|\.[A-Za-z0-9])",
)
# La autoridad de una URL termina en `/`, `?` o `#`, y en un texto también en un cierre o una marca de
# dirección: `(https://)` y `https://` solos, de los textos de ayuda, no tienen host.
# Las marcas de dirección de texto (LRM, RLM, LRE a RLO y LRI a PDI), que rodean una URL en un texto
# de derecha a izquierda o en una seudolocalización.
BIDI_MARKS = "".join(
    chr(code) for code in (0x200E, 0x200F, *range(0x202A, 0x202F), *range(0x2066, 0x206A))
)
AUTHORITY_RE = re.compile(r"[^/?#)\]>,;\s" + BIDI_MARKS + "]*")
HOST_RE = re.compile(r"^[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?)*$")
PRINTABLE_RE = re.compile(rb"[\x20-\x7e]{6,}")
TEXT_SUFFIXES = (
    ".txt", ".md", ".json", ".html", ".htm", ".properties", ".xml", ".csv", ".list", ".version",
    ".textproto", ".pro",
)


class ApkError(Exception):
    """El APK, o una de sus partes, no se pudo leer."""


# --- Lectura de cadenas ------------------------------------------------------------------------------

def uleb128(data, offset):
    """Un entero LEB128 sin signo: devuelve el valor y el desplazamiento siguiente."""
    result = 0
    shift = 0
    while True:
        if offset >= len(data):
            raise ApkError("LEB128 cortado")
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, offset
        shift += 7
        if shift > 35:
            raise ApkError("LEB128 demasiado largo")


def dex_strings(data):
    """Cada cadena de la tabla `string_ids` de un archivo DEX, en MUTF-8 leído como UTF-8 tolerante."""
    if len(data) < 0x70 or not data.startswith(b"dex\n"):
        raise ApkError("no es un archivo DEX")
    size, table = struct.unpack_from("<II", data, 0x38)
    if table + size * 4 > len(data):
        raise ApkError("tabla de cadenas fuera del archivo")
    for index in range(size):
        (offset,) = struct.unpack_from("<I", data, table + index * 4)
        _, start = uleb128(data, offset)
        end = data.find(b"\x00", start)
        if end < 0:
            raise ApkError("cadena sin terminar")
        yield data[start:end].decode("utf-8", errors="replace")


RES_STRING_POOL = 0x0001
RES_CONTAINERS = {0x0002, 0x0003, 0x0200}  # tabla de recursos, XML compilado, paquete


def pool_strings(data, start):
    """Cada cadena de una tabla `ResStringPool` que empieza en [start]."""
    _, header_size, size, count, _, flags, strings_start, _ = struct.unpack_from("<HHIIIIII", data, start)
    utf8 = bool(flags & (1 << 8))
    base = start + strings_start
    for index in range(count):
        (offset,) = struct.unpack_from("<I", data, start + header_size + index * 4)
        at = base + offset
        if utf8:
            _, at = pool_length8(data, at)
            length, at = pool_length8(data, at)
            yield data[at:at + length].decode("utf-8", errors="replace")
        else:
            length, at = pool_length16(data, at)
            yield data[at:at + length * 2].decode("utf-16-le", errors="replace")


def pool_length8(data, at):
    first = data[at]
    if first & 0x80:
        return ((first & 0x7F) << 8) | data[at + 1], at + 2
    return first, at + 1


def pool_length16(data, at):
    (first,) = struct.unpack_from("<H", data, at)
    if first & 0x8000:
        (second,) = struct.unpack_from("<H", data, at + 2)
        return ((first & 0x7FFF) << 16) | second, at + 4
    return first, at + 2


def chunk_strings(data, start=0, end=None):
    """Recorre los bloques de un archivo de recursos o de un XML compilado y devuelve sus cadenas."""
    end = len(data) if end is None else end
    at = start
    while at + 8 <= end:
        kind, header_size, size = struct.unpack_from("<HHI", data, at)
        if size < 8 or header_size < 8 or at + size > end:
            raise ApkError("bloque de recursos inválido")
        if kind == RES_STRING_POOL:
            yield from pool_strings(data, at)
        elif kind in RES_CONTAINERS:
            yield from chunk_strings(data, at + header_size, at + size)
        at += size


def printable_strings(data):
    """Las secuencias imprimibles de un binario, como `strings`."""
    for match in PRINTABLE_RE.finditer(data):
        yield match.group().decode("ascii")


def is_binary_xml(data):
    return len(data) >= 8 and struct.unpack_from("<H", data, 0)[0] == 0x0003


def strings_of(name, data):
    """Las cadenas de un archivo del APK, según su tipo."""
    base = name.rsplit("/", 1)[-1]
    if re.fullmatch(r"classes\d*\.dex", base) and "/" not in name:
        return dex_strings(data)
    if name == "resources.arsc":
        return chunk_strings(data)
    if name.endswith(".xml") and is_binary_xml(data):
        return chunk_strings(data)
    if name.endswith(TEXT_SUFFIXES) or name.startswith(("res/raw", "assets/")):
        text = decompress_if_gzip(data)
        if text is not None:
            return [text.decode("utf-8", errors="replace")]
    return printable_strings(data)


def decompress_if_gzip(data):
    if data[:2] == b"\x1f\x8b":
        try:
            return zlib.decompress(data, 16 + zlib.MAX_WBITS)
        except zlib.error:
            return None
    return data


# --- Hosts -------------------------------------------------------------------------------------------

def normalize_host(raw):
    """El host en minúsculas, sin usuario, puerto ni punto final, o None si no parece un host."""
    host = raw.rsplit("@", 1)[-1].split(":", 1)[0].strip(".").lower()
    if not HOST_RE.match(host) or ("." not in host and host != "localhost"):
        return None
    return host


def hosts_in(text):
    """
    Pares (host, origen) de un texto. El origen es `url`, `correo` o `nombre`; `local` para un esquema
    que no es red, y `armada` para una URL de red cuyo host no es un nombre fijo, como `https://%s/`: se
    arma al correr, así que alguien tiene que mirarla.
    """
    found = []
    for match in URL_RE.finditer(text):
        scheme = match.group(1).lower()
        authority = AUTHORITY_RE.match(match.group(2)).group()
        if scheme in NETWORK_SCHEMES:
            host = normalize_host(authority)
            if host:
                found.append((host, "url"))
            elif authority:
                found.append((scheme + "://" + authority, "armada"))
        elif scheme in LOCAL_SCHEMES:
            found.append((scheme + "://", "local"))
    for match in EMAIL_RE.finditer(text):
        host = normalize_host(match.group(1))
        if host:
            found.append((host, "correo"))
    for match in BARE_RE.finditer(text):
        host = normalize_host(match.group(1))
        # Sin esquema, `l.net` es una columna de SQL y `com.foo.app` un paquete: una primera etiqueta de
        # una letra o de un paquete al revés no cuenta. Con esquema, cualquier host cuenta.
        if host and len(host.split(".", 1)[0]) > 1 and host.split(".", 1)[0] not in PACKAGE_ROOTS:
            found.append((host, "nombre"))
    return found


def host_matches(pattern, host):
    if pattern == "*":
        return True
    if pattern.startswith("*."):
        return host == pattern[2:] or host.endswith(pattern[1:])
    return host == pattern.lower()


def manifest_host(repo_root, override):
    """El host del aviso de versión: el de `--manifest-url`, o el de `update_manifest_url` del repositorio."""
    url = override
    if url is None:
        path = os.path.join(repo_root, UPDATE_STRINGS)
        if not os.path.exists(path):
            return None
        for element in ElementTree.parse(path).getroot().iter("string"):
            if element.get("name") == MANIFEST_URL_KEY:
                url = (element.text or "").strip()
    if not url:
        return None
    match = URL_RE.match(url)
    if not match or match.group(1).lower() != "https":
        raise ApkError("la dirección del aviso de versión no es https://")
    host = normalize_host(AUTHORITY_RE.match(match.group(2)).group())
    if not host:
        raise ApkError("la dirección del aviso de versión no tiene un host válido")
    return host


class Policy:
    """La lista cerrada, con el host del aviso de versión ya resuelto."""

    def __init__(self, update_host):
        suggestion = "Sugerencia de servicio WebDAV, solo si la persona la elige"
        self.destinations = {host: suggestion for host in WEBDAV_SUGGESTION_HOSTS}
        self.links = dict(LINK_HOSTS)
        if update_host:
            self.destinations[update_host] = "Aviso de versión firmado, solo al tocar Buscar actualización"
            self.links.setdefault(update_host, "Páginas públicas de la app")

    def classify(self, host, origin, member):
        """La clase y la razón de [host], encontrado en [member]."""
        if origin == "local":
            return "LOCAL", LOCAL_SCHEMES[host[:-3]]
        if origin == "armada":
            return "FUERA DE LA LISTA", "URL con el host armado al correr: hay que ver qué código la usa"
        if host in self.destinations:
            return "DESTINO", self.destinations[host]
        if host in self.links:
            return "ENLACE", self.links[host]
        for pattern, where, reason in EXCLUSIONS:
            if host_matches(pattern, host) and fnmatch.fnmatchcase(member, where):
                return "EXCLUIDO", reason
        return "FUERA DE LA LISTA", "Nadie lo revisó: detiene la publicación"


def new_entry():
    return {"count": 0, "members": set(), "origins": set(), "samples": []}


def scan(apk_path, policy):
    """Recorre el APK y devuelve {(host, clase, razón): {"count", "members", "origins", "samples"}}."""
    results = {}
    try:
        archive = zipfile.ZipFile(apk_path)
    except (OSError, zipfile.BadZipFile) as error:
        raise ApkError("no se pudo abrir el APK: %s" % error.__class__.__name__)
    with archive:
        names = archive.namelist()
        if "AndroidManifest.xml" not in names or not any(re.fullmatch(r"classes\d*\.dex", n) for n in names):
            raise ApkError("no parece un APK: falta AndroidManifest.xml o classes.dex")
        for member in names:
            if member.endswith("/"):
                continue
            skipped = next(
                (reason for where, reason in SKIPPED_FILES if fnmatch.fnmatchcase(member, where)),
                None,
            )
            if skipped:
                entry = results.setdefault(("(archivo entero)", "EXCLUIDO", skipped), new_entry())
                entry["count"] += 1
                entry["members"].add(member)
                continue
            data = archive.read(member)
            try:
                texts = strings_of(member, data)
                for text in texts:
                    for host, origin in hosts_in(text):
                        kind, reason = policy.classify(host, origin, member)
                        entry = results.setdefault((host, kind, reason), new_entry())
                        entry["count"] += 1
                        entry["members"].add(member)
                        entry["origins"].add(origin)
                        if kind == "FUERA DE LA LISTA" and len(entry["samples"]) < 3:
                            entry["samples"].append(text.strip()[:160])
            except (ApkError, struct.error, IndexError) as error:
                raise ApkError("no se pudo leer %s: %s" % (member, error))
    return results


def report(results, as_json):
    order = {"FUERA DE LA LISTA": 0, "DESTINO": 1, "ENLACE": 2, "EXCLUIDO": 3, "LOCAL": 4}
    rows = sorted(results.items(), key=lambda item: (order[item[0][1]], item[0][0], item[0][2]))
    if as_json:
        payload = [
            {
                "host": host,
                "class": kind,
                "reason": reason,
                "count": entry["count"],
                "files": sorted(entry["members"]),
                "origins": sorted(entry["origins"]),
                "samples": entry["samples"],
            }
            for (host, kind, reason), entry in rows
        ]
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return
    print("| Host | Clase | Veces | Archivos | Razón |")
    print("|---|---|---:|---|---|")
    for (host, kind, reason), entry in rows:
        members = sorted(entry["members"])
        shown = ", ".join(members[:3]) + (" y %d más" % (len(members) - 3) if len(members) > 3 else "")
        print("| `%s` | %s | %d | %s | %s |" % (host, kind, entry["count"], shown, reason))
        for sample in entry["samples"]:
            print("|  |  |  | muestra | `%s` |" % sample.replace("|", "\\|").replace("`", "'"))


def main(argv=None):
    # La consola de Windows no siempre escribe UTF-8: sin esto, las tildes salen rotas o falla la salida.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(
        description="Busca URL y hosts en un APK y los compara con la lista cerrada de destinos.",
    )
    parser.add_argument("apk", help="El APK, por ejemplo app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--manifest-url", help="La dirección https del aviso de versión, si no es la del repositorio")
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--repo", default=repo, help="La raíz del repositorio de código")
    parser.add_argument("--json", action="store_true", help="Escribe el resultado en JSON")
    args = parser.parse_args(argv)
    try:
        policy = Policy(manifest_host(args.repo, args.manifest_url))
        results = scan(args.apk, policy)
    except (ApkError, ElementTree.ParseError) as error:
        print("apk_hosts: %s" % error, file=sys.stderr)
        return 2
    report(results, args.json)
    outside = sorted({host for host, kind, _ in results if kind == "FUERA DE LA LISTA"})
    if outside:
        listed = ", ".join(outside)
        print("\napk_hosts: %d host(s) fuera de la lista cerrada: %s" % (len(outside), listed), file=sys.stderr)
        return 1
    print("\napk_hosts: todos los hosts están en la lista cerrada o excluidos con su razón.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
