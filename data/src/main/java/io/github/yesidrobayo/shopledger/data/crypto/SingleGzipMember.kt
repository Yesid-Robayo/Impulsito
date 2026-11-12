package io.github.yesidrobayo.shopledger.data.crypto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Descomprime un único miembro de gzip, entero y sin nada detrás, como lo escribe la app dentro de
 * una copia cifrada.
 *
 * `GZIPInputStream` acepta miembros seguidos e ignora bytes sueltos tras el final. Dentro del
 * cifrado eso no debe pasar: una copia de la app tiene un solo miembro, y cualquier otra cosa es
 * una copia que no escribió la app, así que se trata como dañada. También comprueba el control
 * CRC-32 y el largo del pie, y lee [source] hasta el final, para que el cifrado autentique el
 * último segmento.
 *
 * No es cifrado: es el formato gzip de RFC 1952, leído con el `Inflater` de la plataforma.
 */
internal object SingleGzipMember {
    private const val ID1 = 0x1F
    private const val ID2 = 0x8B
    private const val DEFLATE = 8
    private const val FHCRC = 0x02
    private const val FEXTRA = 0x04
    private const val FNAME = 0x08
    private const val FCOMMENT = 0x10
    private const val RESERVED_FLAGS = 0xE0
    private const val FIXED_HEADER_REST = 6
    private const val TRAILER_BYTES = 8
    private const val BYTE_MASK = 0xFF
    private const val UINT32_MASK = 0xFFFF_FFFFL

    /**
     * Descomprime [source] y entrega cada trozo a [onChunk]. Lanza `IOException` si no es
     * exactamente un miembro de gzip válido, o si [source] falla, por ejemplo porque un segmento
     * cifrado no autentica.
     */
    fun inflate(
        source: InputStream,
        bufferBytes: Int,
        checkActive: () -> Unit,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        readHeader(source)
        val inflater = Inflater(true)
        try {
            val input = ByteArray(bufferBytes)
            val output = ByteArray(bufferBytes)
            val crc = CRC32()
            var total = 0L
            var inputLength = 0
            while (!inflater.finished()) {
                checkActive()
                if (inflater.needsInput()) {
                    inputLength = source.read(input)
                    if (inputLength < 0) throw EOFException()
                    inflater.setInput(input, 0, inputLength)
                }
                if (inflater.needsDictionary()) throw IOException("Gzip con diccionario")
                val produced =
                    try {
                        inflater.inflate(output)
                    } catch (e: DataFormatException) {
                        throw IOException("Gzip dañado", e)
                    }
                if (produced > 0) {
                    crc.update(output, 0, produced)
                    total += produced
                    onChunk(output, produced)
                }
            }
            // Lo que el inflador no usó es el comienzo del pie.
            val left = inflater.remaining
            val trailer = ByteArray(TRAILER_BYTES)
            val fromBuffer = minOf(left, TRAILER_BYTES)
            input.copyInto(trailer, 0, inputLength - left, inputLength - left + fromBuffer)
            readFully(source, trailer, fromBuffer)
            if (left > TRAILER_BYTES) throw IOException("Datos tras el final de gzip")
            if (littleEndian(trailer, 0) != crc.value) throw IOException("Control de gzip distinto")
            if (littleEndian(trailer, 4) != (total and UINT32_MASK)) {
                throw IOException("Largo de gzip distinto")
            }
            // Nada más: ni otro miembro ni bytes sueltos. Leer hasta el final autentica el último
            // segmento cifrado.
            if (source.read() >= 0) throw IOException("Datos tras el final de gzip")
        } finally {
            inflater.end()
        }
    }

    private fun readHeader(source: InputStream) {
        if (byte(source) != ID1 || byte(source) != ID2 || byte(source) != DEFLATE) {
            throw IOException("No es gzip")
        }
        val flags = byte(source)
        if (flags and RESERVED_FLAGS != 0) throw IOException("Banderas de gzip reservadas")
        // Hora, banderas extra y sistema: no se usan.
        repeat(FIXED_HEADER_REST) { byte(source) }
        if (flags and FEXTRA != 0) {
            val length = byte(source) or (byte(source) shl 8)
            repeat(length) { byte(source) }
        }
        if (flags and FNAME != 0) skipZeroTerminated(source)
        if (flags and FCOMMENT != 0) skipZeroTerminated(source)
        if (flags and FHCRC != 0) repeat(2) { byte(source) }
    }

    private fun skipZeroTerminated(source: InputStream) {
        while (byte(source) != 0) {
            // Solo avanza hasta el cero.
        }
    }

    private fun byte(source: InputStream): Int {
        val value = source.read()
        if (value < 0) throw EOFException()
        return value and BYTE_MASK
    }

    private fun readFully(source: InputStream, target: ByteArray, offset: Int) {
        var position = offset
        while (position < target.size) {
            val read = source.read(target, position, target.size - position)
            if (read < 0) throw EOFException()
            position += read
        }
    }

    private fun littleEndian(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }
}
