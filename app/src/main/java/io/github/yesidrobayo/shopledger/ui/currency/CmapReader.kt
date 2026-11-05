package io.github.yesidrobayo.shopledger.ui.currency

/**
 * Lee los puntos de código que una fuente TrueType declara en su tabla `cmap`.
 *
 * Hace falta porque `Paint.hasGlyph` no responde si Figtree o `currency_symbols` tienen un
 * símbolo: un `Typeface` cargado desde un archivo trae detrás el respaldo del sistema, y
 * `hasGlyph` dice que sí aunque el glifo lo ponga otra fuente. Solo la `cmap` del archivo dice
 * qué dibuja esa fuente por sí sola.
 *
 * Solo lee las fuentes propias del APK, así que no se defiende de archivos hostiles: un archivo
 * mal formado lanza una excepción y no devuelve datos parciales.
 */
object CmapReader {
    private const val TABLE_RECORDS_START = 12
    private const val TABLE_RECORD_SIZE = 16
    private const val MAX_CODE_POINT = 0x10FFFF

    /** Devuelve los puntos de código con un glifo distinto de `.notdef`. */
    fun readCodePoints(font: ByteArray): Set<Int> {
        val data = BigEndianBytes(font)
        val cmap = findTable(data, "cmap")
        val result = HashSet<Int>()
        val subtableCount = data.u16(cmap + 2)
        for (index in 0 until subtableCount) {
            val record = cmap + 4 + 8 * index
            val platformId = data.u16(record)
            val encodingId = data.u16(record + 2)
            if (!isUnicode(platformId, encodingId)) continue
            val subtable = cmap + data.u32(record + 4).toInt()
            when (data.u16(subtable)) {
                4 -> readFormat4(data, subtable, result)
                12 -> readFormat12(data, subtable, result)
            }
        }
        return result
    }

    private fun findTable(data: BigEndianBytes, tag: String): Int {
        val tableCount = data.u16(4)
        for (index in 0 until tableCount) {
            val record = TABLE_RECORDS_START + TABLE_RECORD_SIZE * index
            if (data.tag(record) == tag) return data.u32(record + 8).toInt()
        }
        throw IllegalArgumentException("La fuente no tiene la tabla $tag")
    }

    // Unicode de la plataforma 0, o de Windows con BMP (1) o completo (10). Las demás
    // codificaciones no aportan puntos de código Unicode.
    private fun isUnicode(platformId: Int, encodingId: Int): Boolean =
        platformId == 0 || (platformId == 3 && (encodingId == 1 || encodingId == 10))

    private fun readFormat4(data: BigEndianBytes, subtable: Int, out: MutableSet<Int>) {
        val segCount = data.u16(subtable + 6) / 2
        val endCodes = subtable + 14
        val startCodes = endCodes + 2 * segCount + 2
        val idDeltas = startCodes + 2 * segCount
        val idRangeOffsets = idDeltas + 2 * segCount
        for (segment in 0 until segCount) {
            val start = data.u16(startCodes + 2 * segment)
            val end = data.u16(endCodes + 2 * segment)
            val idDelta = data.u16(idDeltas + 2 * segment)
            val rangeOffsetPosition = idRangeOffsets + 2 * segment
            val idRangeOffset = data.u16(rangeOffsetPosition)
            for (codePoint in start..end) {
                if (codePoint == 0xFFFF) continue
                val glyph =
                    if (idRangeOffset == 0) {
                        (codePoint + idDelta) and 0xFFFF
                    } else {
                        val offsetInRange = idRangeOffset + 2 * (codePoint - start)
                        val raw = data.u16(rangeOffsetPosition + offsetInRange)
                        if (raw == 0) 0 else (raw + idDelta) and 0xFFFF
                    }
                if (glyph != 0) out += codePoint
            }
        }
    }

    private fun readFormat12(data: BigEndianBytes, subtable: Int, out: MutableSet<Int>) {
        val groupCount = data.u32(subtable + 12).toInt()
        for (group in 0 until groupCount) {
            val record = subtable + 16 + 12 * group
            val start = data.u32(record)
            val end = minOf(data.u32(record + 4), MAX_CODE_POINT.toLong())
            val startGlyph = data.u32(record + 8)
            for (codePoint in start..end) {
                if (startGlyph + (codePoint - start) != 0L) out += codePoint.toInt()
            }
        }
    }

    private class BigEndianBytes(private val bytes: ByteArray) {
        fun u16(at: Int): Int = (byteAt(at) shl 8) or byteAt(at + 1)

        private fun byteAt(at: Int): Int = bytes[at].toInt() and 0xFF

        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        fun tag(at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
    }
}
