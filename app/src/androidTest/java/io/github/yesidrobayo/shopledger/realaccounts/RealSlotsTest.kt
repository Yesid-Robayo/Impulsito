package io.github.yesidrobayo.shopledger.realaccounts

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.realaccounts.RealAccounts.Kind
import io.github.yesidrobayo.shopledger.realaccounts.RealAccounts.report
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ranuras con Drive, sobre los tres documentos que la persona creó con el selector del
 * sistema. Solo lee, salvo [deletesTheThreeSlots], que además pide `-e deleteSlots true`.
 *
 * Lee `slots.json` de la app para saber qué documento es cada ranura y qué tamaño y suma debería
 * tener. Las URI y los nombres de los documentos solo viven en memoria: al registro van la letra
 * de la ranura, tamaños, coincidencias y clases de excepción. Las sumas completas de lo que se
 * releyó, que son de datos inventados, quedan en la carpeta privada de la app para compararlas
 * con lo que baje la persona desde la web de Drive.
 */
@RunWith(AndroidJUnit4::class)
class RealSlotsTest {
    private val resolver get() = RealAccounts.context.contentResolver

    @Before
    fun onlyWithSlots() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        assumeTrue(
            "Solo con la nube del teléfono",
            RealAccounts.connectedKind() == BackupDestinationKind.DOCUMENT_SLOTS,
        )
    }

    /** Las tres URI con permiso persistente, todas de Drive, y el modo de escritura que quedó. */
    @Test
    fun keepsThreeDriveGrantsAndTheWriteMode() {
        val state = slotState()
        val grants = resolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
        val granted = grants.map { it.uri.toString() }.toSet()
        report(
            "slotGrants",
            "grants" to grants.size,
            "drive" to grants.count { it.uri.authority == RealAccounts.DRIVE_AUTHORITY },
            "slotsGranted" to state.slots.count { it.uri in granted },
            "writeMode" to Kind(state.writeMode),
            "sessionRequired" to state.sessionRequired,
        )
        assertEquals(SLOT_COUNT, grants.size)
        assertTrue(grants.all { it.uri.authority == RealAccounts.DRIVE_AUTHORITY })
        assertTrue(state.slots.all { it.uri in granted })
    }

    /**
     * El tamaño que informa Drive, leído dos veces seguidas, y frente al que guardó la app al
     * confirmar la copia de cada ranura. Si cambia entre dos lecturas, no sirve de guarda.
     */
    @Test
    fun columnSizeIsStable() = runBlocking<Unit> {
        val state = slotState()
        val first = state.slots.associate { it.letter to sizeOf(it.uri) }
        delay(SECOND_READ_DELAY_MS)
        val second = state.slots.associate { it.letter to sizeOf(it.uri) }
        for (slot in state.slots) {
            report(
                "columnSize",
                "slot" to Kind(slot.letter),
                "known" to (first[slot.letter] != null),
                "stable" to (first[slot.letter] == second[slot.letter]),
                "matchesExpected" to
                    (slot.expectedSize == null || slot.expectedSize == first[slot.letter]),
                "bytes" to first[slot.letter],
            )
        }
        assertTrue(state.slots.all { first[it.letter] == second[it.letter] })
    }

    /** Relee cada ranura entera y compara su suma con la que guardó la app. */
    @Test
    fun rereadMatchesTheRecordedSha() {
        val state = slotState()
        val lines = mutableListOf<String>()
        for (slot in state.slots) {
            val started = System.currentTimeMillis()
            val read = runCatching { shaAndSizeOf(slot.uri) }
            val (sha, size) = read.getOrNull() ?: (null to null)
            report(
                "reread",
                "slot" to Kind(slot.letter),
                "read" to read.isSuccess,
                "failure" to read.exceptionOrNull()?.let { Kind(it::class.java.simpleName) },
                "shaMatches" to (sha != null && sha == slot.expectedSha),
                "bytes" to size,
                "ms" to System.currentTimeMillis() - started,
            )
            lines += "${slot.letter} ${size ?: "-"} ${sha ?: "-"} ${slot.writtenAt ?: "-"}"
        }
        // Datos inventados: el integrador los compara con lo que baje la persona desde la web.
        File(RealAccounts.privateDir(), SHA_FILE).writeText(lines.joinToString("\n") + "\n")
        assertTrue(state.slots.all { it.expectedSha == null || it.expectedSha == shaOrNull(it.uri) })
    }

    /** Una app normal no puede leer las raíces del proveedor: por eso la lista es fija. */
    @Test
    fun cannotReadTheProviderRoots() {
        val outcome = runCatching {
            resolver.query(
                DocumentsContract.buildRootsUri(RealAccounts.DRIVE_AUTHORITY),
                null,
                null,
                null,
                null,
            )?.use { it.count }
        }
        report(
            "roots",
            "readable" to outcome.isSuccess,
            "failure" to outcome.exceptionOrNull()?.let { Kind(it::class.java.simpleName) },
        )
        assertTrue("Las raíces no deberían leerse", outcome.isFailure)
    }

    /**
     * Lo que Drive declara de cada documento: si deja borrarlo, escribirlo y renombrarlo, y
     * cuántas apps visibles para la app abren `ACTION_VIEW` con su URI. Sin `<queries>`, Android
     * esconde casi todas: ese conteo es solo una cota inferior.
     */
    @Test
    fun describesTheDocumentFlagsAndViewers() {
        val state = slotState()
        for (slot in state.slots) {
            val flags = flagsOf(slot.uri)
            val viewers = RealAccounts.context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse(slot.uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                0,
            ).size
            report(
                "documentFlags",
                "slot" to Kind(slot.letter),
                "known" to (flags != null),
                "supportsDelete" to flags?.has(DocumentsContract.Document.FLAG_SUPPORTS_DELETE),
                "supportsWrite" to flags?.has(DocumentsContract.Document.FLAG_SUPPORTS_WRITE),
                "supportsRename" to flags?.has(DocumentsContract.Document.FLAG_SUPPORTS_RENAME),
                "viewers" to viewers,
            )
        }
    }

    /**
     * Si Drive deja borrar los tres documentos de las ranuras, como lo necesita borrar los datos.
     * Destruye las copias de la nube: es lo último de la sesión, y solo con `-e deleteSlots true`.
     * Solo toca los tres documentos que escribió la app.
     */
    @Test
    fun deletesTheThreeSlots() {
        assumeTrue(RealAccounts.argument("deleteSlots", "false") == "true")
        val state = slotState()
        for (slot in state.slots) {
            val outcome = runCatching {
                DocumentsContract.deleteDocument(resolver, Uri.parse(slot.uri))
            }
            report(
                "deleteSlot",
                "slot" to Kind(slot.letter),
                "deleted" to (outcome.getOrNull() == true),
                "failure" to outcome.exceptionOrNull()?.let { Kind(it::class.java.simpleName) },
            )
        }
    }

    private fun Int.has(flag: Int): Boolean = this and flag != 0

    private fun sizeOf(uri: String): Long? = runCatching {
        resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst() || it.isNull(0)) null else it.getLong(0)
        }
    }.getOrNull()

    private fun flagsOf(uri: String): Int? = runCatching {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        resolver.query(Uri.parse(uri), columns, null, null, null)?.use {
            if (!it.moveToFirst() || it.isNull(0)) null else it.getInt(0)
        }
    }.getOrNull()

    private fun shaOrNull(uri: String): String? = runCatching { shaAndSizeOf(uri).first }.getOrNull()

    private fun shaAndSizeOf(uri: String): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val input = checkNotNull(resolver.openInputStream(Uri.parse(uri))) { "Sin flujo" }
        input.use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }

    /** Lo que guarda `slots.json`, solo lo que necesitan estas pruebas. */
    private fun slotState(): SlotsFile {
        val file = File(RealAccounts.context.noBackupFilesDir, SLOTS_FILE)
        check(file.isFile) { "Falta el estado de las ranuras" }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val slots = root.getJSONObject("slots")
        val entries = listOf("A", "B", "C").map { letter ->
            val entry = slots.getJSONObject(letter)
            val copy = entry.optJSONObject("copy")
            SlotFile(
                letter = letter,
                uri = entry.getString("uri"),
                expectedSize = copy?.getLong("size"),
                expectedSha = copy?.getString("sha256"),
                writtenAt = copy?.getLong("written_at"),
            )
        }
        return SlotsFile(
            slots = entries,
            writeMode = root.getString("write_mode"),
            sessionRequired = root.optBoolean("session_required", false),
        )
    }

    private class SlotsFile(
        val slots: List<SlotFile>,
        val writeMode: String,
        val sessionRequired: Boolean,
    )

    /** Una ranura. [uri] nunca va al registro. */
    private class SlotFile(
        val letter: String,
        val uri: String,
        val expectedSize: Long?,
        val expectedSha: String?,
        val writtenAt: Long?,
    )

    private companion object {
        const val SLOT_COUNT = 3
        const val SLOTS_FILE = "slots.json"
        const val SHA_FILE = "slots-sha.txt"
        const val SECOND_READ_DELAY_MS = 2_000L
        const val BUFFER_BYTES = 64 * 1024
    }
}
