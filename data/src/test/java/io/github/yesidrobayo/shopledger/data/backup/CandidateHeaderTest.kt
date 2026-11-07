package io.github.yesidrobayo.shopledger.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El encabezado de una candidata se revisa por sus bytes antes de abrirla: firma, diario normal,
 * páginas enteras y una versión del esquema que esta app conoce.
 */
class CandidateHeaderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a sound header gives the schema version`() {
        assertEquals(HeaderResult.Valid(1), parse(header(userVersion = 1)))
        assertEquals(HeaderResult.Valid(3), parse(header(userVersion = 3)))
    }

    @Test
    fun `a text file is not a database`() {
        val text = "esto no es una base de datos, solo texto inventado ".repeat(4)
        val bytes = text.toByteArray(Charsets.US_ASCII).copyOf(CandidateHeader.SIZE)
        assertEquals(rejected(CandidateRejection.NOT_A_DATABASE), parse(bytes, PAGE * 2))
    }

    @Test
    fun `the user version must be one the app knows`() {
        assertEquals(
            rejected(CandidateRejection.UNKNOWN_SCHEMA_VERSION),
            parse(header(userVersion = 0)),
        )
        assertEquals(
            rejected(CandidateRejection.UNKNOWN_SCHEMA_VERSION),
            parse(header(userVersion = -1)),
        )
        // Una copia de una app más nueva pide actualizar, nunca se da por dañada.
        assertEquals(rejected(CandidateRejection.SCHEMA_TOO_NEW), parse(header(userVersion = 4)))
    }

    @Test
    fun `a database in write ahead mode is refused`() {
        val write = header().also { it[18] = 2 }
        val read = header().also { it[19] = 2 }
        assertEquals(rejected(CandidateRejection.WAL_DATABASE), parse(write))
        assertEquals(rejected(CandidateRejection.WAL_DATABASE), parse(read))
        // Un modo que SQLite no conoce tampoco pasa.
        assertEquals(
            rejected(CandidateRejection.NOT_A_DATABASE),
            parse(
                header().also {
                    it[18] = 3
                },
            ),
        )
        assertEquals(
            rejected(CandidateRejection.NOT_A_DATABASE),
            parse(
                header().also {
                    it[19] = 0
                },
            ),
        )
    }

    @Test
    fun `the file is a whole number of valid pages`() {
        assertEquals(rejected(CandidateRejection.NOT_A_DATABASE), parse(header(), PAGE * 4 + 100))
        assertEquals(rejected(CandidateRejection.NOT_A_DATABASE), parse(header(pageSize = 1000)))
        assertEquals(rejected(CandidateRejection.NOT_A_DATABASE), parse(header(pageSize = 256)))
        // El valor 1 es una página de 65.536 bytes.
        assertEquals(HeaderResult.Valid(1), parse(header(pageSize = 1, pages = 2), 65_536L * 2))
    }

    @Test
    fun `a database cut short of its declared pages is refused`() {
        val cut = header(pages = 10)
        assertEquals(rejected(CandidateRejection.NOT_A_DATABASE), parse(cut, PAGE * 9))
        assertEquals(HeaderResult.Valid(1), parse(cut, PAGE * 10))
        // Si el contador que valida el número de páginas no coincide, ese número no se usa.
        val stale = header(pages = 10).also { it[95] = 9 }
        assertEquals(HeaderResult.Valid(1), parse(stale, PAGE * 9))
    }

    @Test
    fun `a file shorter than a header is refused when read from disk`() {
        val file = File(folder.root, "candidate.db").apply { writeBytes(header().copyOf(40)) }
        assertEquals(
            rejected(CandidateRejection.NOT_A_DATABASE),
            CandidateHeader.read(file, APP_VERSION),
        )
        val whole = File(folder.root, "whole.db").apply {
            writeBytes(header().copyOf(PAGE.toInt() * 4))
        }
        assertEquals(HeaderResult.Valid(1), CandidateHeader.read(whole, APP_VERSION))
    }

    private fun parse(bytes: ByteArray, length: Long = PAGE * 4): HeaderResult =
        CandidateHeader.parse(bytes, length, APP_VERSION)

    private fun rejected(reason: CandidateRejection) = HeaderResult.Rejected(reason)

    /** Un encabezado de SQLite con lo mínimo que se revisa, y lo demás en cero. */
    private fun header(
        userVersion: Int = 1,
        pageSize: Int = PAGE.toInt(),
        pages: Int = 4,
    ): ByteArray {
        val bytes = ByteArray(CandidateHeader.SIZE)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[16] = (pageSize ushr 8).toByte()
        bytes[17] = pageSize.toByte()
        bytes[18] = 1
        bytes[19] = 1
        putInt(bytes, 24, 7)
        putInt(bytes, 28, pages)
        putInt(bytes, 60, userVersion)
        putInt(bytes, 92, 7)
        return bytes
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (24 - 8 * index)).toByte()
    }

    private companion object {
        const val PAGE = 4096L
        const val APP_VERSION = 3
    }
}
