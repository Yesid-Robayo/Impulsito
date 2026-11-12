# Formato de las copias cifradas, versión 2

Esta carpeta publica el formato de las copias cifradas de la app, sus vectores de prueba y una herramienta para abrir una copia en un
PC **solo con el código de recuperación**, sin la app. Sirve si el teléfono se pierde, si la app deja de instalarse o si alguien quiere
escribir otra implementación.

La app no lee nada de esta carpeta. El cifrado es opcional y viene apagado: quien no lo activa tiene copias `.db.gz` en claro, que se
abren con cualquier programa de gzip.

| Archivo | Qué es |
|---|---|
| `README.md` | Esta especificación |
| `rescue.py` | La herramienta de rescate |
| `vectors/format-v2-vectors.json` | Vectores de prueba: códigos, copias y alteraciones, todo inventado |
| `make_vectors.py` | Cómo se generaron los vectores |
| `test_rescue.py` | Pruebas de la herramienta contra los vectores |

## Abrir una copia

Hace falta Python 3.9 o superior y la biblioteca [Tink](https://developers.google.com/tink) oficial de Google, la misma familia que usa
la app. La versión probada es la 1.16.1:

```text
python -m venv rescate
rescate/bin/python -m pip install tink==1.16.1          (en Windows: rescate\Scripts\python)
rescate/bin/python rescue.py copia.slbk --out base.db
```

La herramienta pide el código sin mostrarlo. Se puede escribir con o sin guiones, en mayúsculas o minúsculas. La salida es la base
SQLite de la app, la misma que había en el teléfono, y se abre con cualquier programa de SQLite. Una foto cifrada (`.slmd`) se abre igual,
con `--out foto.webp`; la extensión `.slmd` basta, o `--media` si el archivo tiene otro nombre.

Lo que la herramienta promete:

- **El código nunca sale de la memoria.** No se acepta como argumento, para que no quede en el historial de la consola, y no se escribe
  en pantalla, en un archivo ni en un registro. Si la entrada estándar no es una consola, lee una sola línea de ella.
- **Nunca reemplaza un archivo.** Si `--out` ya existe, se detiene.
- **No deja nada a medias.** Escribe en un temporal junto a la salida, lo sincroniza con el disco y solo entonces lo renombra. Si algo
  falla, borra el temporal.
- **Un código mal escrito no hace nada.** Los 2 caracteres de control lo detectan antes de abrir la copia, y no hay límite de intentos.
- Los mensajes salen en español, inglés o portugués, según el sistema o `--lang es|en|pt`. Cada motivo tiene su propio código de salida:

| Salida | Motivo | Qué significa |
|---:|---|---|
| 0 | `OK` | La copia se abrió, y la salida tiene `raw_size` bytes. Imprime su SHA-256 |
| 2 | `CODE_INVALID` | El código no tiene la forma correcta o sus caracteres de control no coinciden |
| 3 | `NOT_A_BACKUP` | El archivo no empieza con la firma del formato |
| 4 | `NEWER_FORMAT` | `format_version` mayor que la conocida: hace falta una herramienta más nueva. No es daño |
| 5 | `HEADER_OUT_OF_LIST` | Un valor de la cabecera fuera de la lista cerrada. Se rechaza antes de usar la clave |
| 6 | `OTHER_CODE` | El código es válido, pero la copia se hizo con otro |
| 7 | `DAMAGED` | Falló la autenticación, el archivo está truncado, o gzip no cierra bien: CRC o largo distintos, más de un miembro o bytes sobrantes |
| 8 | `SIZE_MISMATCH` | Lo descifrado no mide `raw_size` |
| 9 | `TOO_LARGE` | `raw_size` supera 1 GiB, el tope que la app nunca pasa |
| 10 | `NOT_SQLITE` | Se esperaba una base y el contenido no empieza con `SQLite format 3` |
| 11 | `PLAIN_FORMAT` | Es una copia en claro, `.db.gz`: se abre con gzip |
| 12 | `OUTPUT_EXISTS` | El archivo de salida ya existe |
| 13 | `UNREADABLE` | No se pudo leer la copia ni escribir la salida |

## Formatos

El formato sale siempre de los primeros bytes, nunca del nombre del archivo:

| Formato | Primeros bytes | Extensión | Contenido |
|---|---|---|---|
| 1 | `1F 8B` | `.db.gz` | La base SQLite con gzip, en claro. Es el valor por defecto |
| 2 | `53 4C 42 4B` | `.slbk` | La base SQLite con gzip, cifrada |
| 2, foto | `53 4C 42 4B` | `.slmd` | Una foto WebP con gzip, cifrada con la misma clave |

En algunos destinos el archivo se llama `.slcopy`, con los mismos bytes. `SLBK` viene del nombre interno del proyecto, que no cambia.
Junto a una copia puede haber un archivo de metadatos `.slmeta`: si dice algo distinto de la cabecera, manda la cabecera, que va
autenticada.

## El código de recuperación

La app genera 130 bits aleatorios y los muestra como 26 caracteres de [Crockford Base32](https://www.crockford.com/base32.html), más 2 de
control, en 7 grupos de 4: `G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF51`. El alfabeto no tiene I, L, O ni U, así que no forma palabras y no
depende de un idioma.

```text
alfabeto  = 0123456789ABCDEFGHJKMNPQRSTVWXYZ
código    = los 26 caracteres canónicos, en ASCII y mayúsculas, sin guiones ni control
control   = los primeros 10 bits de SHA-256(código), en 2 caracteres del alfabeto
            primero = digest[0] >> 3
            segundo = ((digest[0] & 0x07) << 2) | (digest[1] >> 6)
impreso   = (código + control) en grupos de 4, separados por "-"
```

Al leer lo que alguien escribió: se pasa a mayúsculas, se quitan guiones, espacios y saltos de línea, I y L pasan a 1, y O pasa a 0.
Debe quedar con 28 caracteres del alfabeto, y los 2 últimos deben ser el control de los 26 primeros. Si no, el código es inválido y no se
usa para nada.

## Derivación de la clave

Con [HKDF-SHA256](https://www.rfc-editor.org/rfc/rfc5869), sin función lenta: 130 bits aleatorios no se adivinan por fuerza bruta.

```text
BK      = HKDF-SHA256(ikm = código, salt = "SLBK-v2", info = "backup-aead", 32 bytes)
key_id  = HKDF-SHA256(ikm = código, salt = "SLBK-v2", info = "key-id", 8 bytes)
```

`código` son los 26 caracteres canónicos en ASCII. `salt` e `info` también van en ASCII. `BK` es la clave de todas las copias de ese
código, y `key_id` dice qué código abre una copia sin revelar nada de él. Con el código del ejemplo:

| Campo | Valor |
|---|---|
| Control | `51` |
| `key_id` | `8755f48d645c0bad` |
| SHA-256 de `BK` | `0164321177d81349f81b0f3b5acce5ce20200bbd319222427cc90f96279881f8` |

## Cabecera

La copia empieza con esta cabecera, en claro. Todos los enteros van en big-endian. No lleva moneda, región, zona ni idioma: todo instante
es UTC, y lo demás vive dentro de la base.

| Desplazamiento | Tamaño | Campo | Valor |
|---:|---:|---|---|
| 0 | 8 | `magic` | `53 4C 42 4B 0D 0A 1A 0A`. La cola detecta una transferencia en modo texto |
| 8 | 2 | `format_version` | u16, `2` |
| 10 | 2 | `header_len` | u16, largo total de la cabecera, de 68 a 4096 |
| 12 | 1 | `aead_id` | `1`: AES-256-GCM-HKDF-SHA256 en flujo, segmentos de 1.048.576 bytes |
| 13 | 1 | `key_source` | `1`: el código de recuperación |
| 14 | 1 | `payload_codec` | `1`: gzip |
| 15 | 1 | `reserved` | `0` |
| 16 | 8 | `key_id` | Ver la derivación |
| 24 | 8 | `generation` | u64, número de la copia. `0` en una foto |
| 32 | 4 | `schema_version` | u32, versión del esquema de la base. `0` en una foto |
| 36 | 8 | `created_at_utc_ms` | i64, milisegundos desde 1970-01-01T00:00Z |
| 44 | 16 | `device_id` | UUID binario del teléfono que hizo la copia |
| 60 | 8 | `raw_size` | u64, bytes del archivo sin comprimir ni cifrar |
| 68 | variable | extensiones | Hasta `header_len`: `tag` u16, `length` u16 y `length` bytes |

El formato 2 no define ninguna extensión. Una extensión desconocida se ignora, pero va autenticada con el resto. Las extensiones deben
llenar la cabecera exacta: un resto de menos de 4 bytes, o una extensión que pase de `header_len`, es un valor fuera de la lista.

## Cifrado

Después de la cabecera sigue un flujo [AES-GCM-HKDF Streaming de Tink](https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming),
con estos parámetros:

| Parámetro | Valor |
|---|---|
| Clave | `BK`, 32 bytes |
| Clave derivada por copia | 32 bytes, AES-256-GCM |
| Hash de HKDF | SHA-256 |
| `ciphertext_segment_size` | 1.048.576 bytes, como la plantilla `AES256_GCM_HKDF_1MB` |
| Datos asociados | **La cabecera completa**, de `magic` al final de las extensiones |

Cómo es el flujo, según la especificación de Tink:

```text
flujo     = len(TinkHeader) || Salt || NoncePrefix || segmento_0 || segmento_1 || ...
            1 byte, valor 40   32 bytes  7 bytes
clave     = HKDF-SHA256(ikm = BK, salt = Salt, info = datos asociados, 32 bytes)
segmento  = AES-GCM(clave, IV = NoncePrefix || i como u32 big-endian || b, sin datos asociados), etiqueta de 16 bytes
            b = 1 en el último segmento y 0 en los demás
```

Cada segmento cifrado mide 1.048.576 bytes, salvo el último. El primero lleva menos texto, porque comparte su espacio con los 40 bytes del
inicio del flujo: 1.048.520 bytes de texto en el primero y 1.048.560 en los demás. Como la cabecera entra en la derivación de la clave,
cambiar un solo byte de ella hace fallar el primer segmento. Cada copia tiene su propio `Salt` aleatorio, así que su clave es distinta
aunque el código sea el mismo. La sobrecarga total es de 68 bytes de cabecera, 40 del flujo y 16 por segmento.

Cómo entra `BK` en Tink:

- **En Java:** `AesGcmHkdfStreamingKey.create(parámetros, SecretBytes.copyFrom(BK, ...))` y `AesGcmHkdfStreaming.create(clave)`.
- **En Python:** Tink no recibe la clave suelta. `rescue.py` arma en memoria un keyset de una sola clave `AesGcmHkdfStreamingKey`, con
  prefijo `RAW`, y nunca lo escribe.

El texto descifrado es **un solo miembro** de [gzip](https://www.rfc-editor.org/rfc/rfc1952), con su CRC-32 y su largo comprobados, y
sin nada después. Dos miembros seguidos, o bytes sobrantes tras el final, son `DAMAGED`: la app siempre escribe uno solo, así que otra
cosa solo puede venir de un escritor con un fallo. Una copia en claro, `.db.gz`, sí admite varios miembros. Descomprimido debe medir
exactamente `raw_size` bytes. En una base, empieza con `SQLite format 3` y un byte cero.

## Orden de lectura

Un lector correcto valida en este orden, y no usa la clave mientras la cabecera no pase:

1. El código: forma y control. Si falla, `CODE_INVALID`, sin abrir la copia.
2. Los primeros bytes: `1F 8B` es formato 1, en claro. Sin `magic`, no es una copia.
3. `format_version`: mayor que 2 pide una versión nueva, nunca dice "copia dañada". Menor, fuera de la lista.
4. `header_len`, `aead_id`, `key_source`, `payload_codec`, `reserved` y las extensiones, contra la lista cerrada.
5. `raw_size`: más de 1 GiB no se abre.
6. `key_id`: si no es el del código, `OTHER_CODE`. No se intenta descifrar.
7. Descifrar en flujo, con la cabecera completa como datos asociados. Un segmento alterado, reordenado, de otra copia o faltante detiene
   todo con `DAMAGED`.
8. Descomprimir un solo miembro de gzip con un tope de salida, sin pasar de `raw_size`, comprobar su CRC y su largo, que no sobre
   nada, el largo final y, en una base, el encabezado de SQLite.

La herramienta no lee la base: lo que hay dentro es asunto de quien la abra. La app, al restaurar, sí la valida entera antes de usarla.

## Vectores de prueba

`vectors/format-v2-vectors.json` tiene todo lo necesario para probar otra implementación. Los dos códigos son sintéticos y los datos,
inventados: ninguna clave real.

| Sección | Qué trae |
|---|---|
| `parameters` | `salt`, las dos `info`, el tamaño del segmento, el tope de 1 GiB, y la fecha y el `device_id` de las cabeceras |
| `codes` | Cada código impreso y canónico, su control, su `key_id` y el SHA-256 de su `BK` |
| `typed_codes` | Textos escritos a mano y su resultado: `VALID` con el canónico, `WRONG_LENGTH`, `INVALID_CHARACTER` o `CHECK_MISMATCH` |
| `copies` | Cada copia en `file_base64`, con su código, su clase (`database` o `media`), el resultado esperado y, si abre, el SHA-256 y el largo del contenido |
| `mutations` | Alteraciones sobre una copia (`base`) y su resultado esperado |

Las alteraciones son operaciones sobre los bytes de la copia base:

| `op` | Efecto |
|---|---|
| `none` | Ninguno: prueba otro código sobre la copia intacta |
| `xor` | `byte[offset] ^= mask` |
| `set` | Reemplaza desde `offset` con los bytes de `hex` |
| `truncate` | Deja los primeros `length` bytes |
| `append` | Agrega los bytes de `hex` al final |
| `header_from` | Los primeros `length` bytes de otra copia (`copy`) y el resto de la base |
| `body_from` | Los primeros `length` bytes de la base y el resto de otra copia (`copy`) |

Las copias de los vectores son de un solo segmento, para que el archivo siga siendo pequeño. `test_rescue.py` cifra en el momento una
de cuatro segmentos y comprueba que truncarla en el límite de un segmento o a mitad, quitarle el último o intercambiar dos se rechaza.

Para comprobar la herramienta:

```text
rescate/bin/python -m unittest test_rescue -v
```

`make_vectors.py` muestra cómo se hicieron los vectores. Cada corrida da archivos distintos, porque Tink elige un `Salt` y un
`NoncePrefix` aleatorios: el JSON versionado es la referencia, y solo se regenera si cambia el formato.
