package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.FlatJson
import io.github.yesidrobayo.shopledger.data.destination.SHA256_HEX
import java.io.IOException

/**
 * En qué punto va el reemplazo de la base. Cada estado garantiza algo en disco, y con eso el
 * arranque decide sin adivinar si termina o vuelve atrás. Los nombres se guardan en el diario: no
 * cambian nunca.
 */
internal enum class RestoreJournalState {
    /** La base vieja sigue en su carpeta, o a medio mover. El candidato espera en la suya. */
    PREPARED,

    /** La base vieja está completa en `previous/`. */
    OLD_MOVED,

    /** El candidato está en la carpeta de bases y falta verificarlo. */
    SWAPPED,

    /** La base buena está en `previous/`, o ya volvió. Se escribe antes de borrar nada. */
    ROLLING_BACK,

    /** La base vieja volvió a su carpeta. */
    ROLLED_BACK,

    /** El candidato quedó verificado en la carpeta de bases. */
    DONE,
}

/**
 * Lo que el arranque necesita para terminar el reemplazo o deshacerlo.
 *
 * @property expectedSha256 la suma del candidato preparado. Leer tablas sin error no prueba nada:
 *   si faltara el archivo, Room crearía una base vacía y la lectura pasaría.
 * @property expectedChangeSeq y [expectedRecordCount] lo que debe leerse en la base nueva al abrirla.
 * @property hadActiveDb si había una base que devolver. Sin ella, que `previous/` esté vacía no
 *   significa que ya se devolvió.
 * @property safetyCopyOk si quedó copia de seguridad. Sin ella, la base vieja no se borra: se aparta
 *   en `damaged-original/`.
 * @property shaVerified si la suma ya se comprobó. Abrir la base la pasa a WAL y cambia su suma: sin
 *   esta marca, morir después de abrirla revertiría una restauración correcta.
 */
internal data class RestoreJournal(
    val state: RestoreJournalState,
    val expectedSha256: String,
    val expectedChangeSeq: Long,
    val expectedRecordCount: Long,
    val hadActiveDb: Boolean,
    val safetyCopyOk: Boolean,
    val shaVerified: Boolean = false,
) {
    init {
        require(SHA256_HEX.matches(expectedSha256)) { "La suma del diario no tiene su forma" }
        require(expectedChangeSeq >= 0 && expectedRecordCount >= 0) {
            "Los contadores del diario no son negativos"
        }
    }
}

/** Lo que dio leer el diario. */
internal sealed interface RestoreJournalRead {
    /** No hay diario: no hay reemplazo en curso. */
    data object Absent : RestoreJournalRead

    /** Existe y no se entiende. No debería pasar, porque se escribe de forma atómica. */
    data object Unreadable : RestoreJournalRead

    data class Present(val journal: RestoreJournal) : RestoreJournalRead
}

/**
 * Lee y escribe `restore/journal.json`, siempre con temporal, sincronización y renombrado.
 *
 * Es un objeto JSON plano, con las claves de la documentación. Se lee al pie de la letra: una clave
 * que falta, una de más, un tipo distinto o un estado desconocido lo hacen ilegible. Un diario de
 * otra versión de la app se trata como ilegible y nunca como uno mal entendido, porque decide qué
 * base se borra.
 */
internal class RestoreJournalStore(
    private val files: RestoreFiles,
    private val ops: DurableFileOps,
) {
    fun read(): RestoreJournalRead {
        if (!files.journal.exists()) return RestoreJournalRead.Absent
        val text =
            try {
                files.journal.readBytes().also { if (it.size > MAX_BYTES) return unreadable() }
                    .toString(Charsets.UTF_8)
            } catch (_: IOException) {
                return unreadable()
            }
        val members = FlatJson.parse(text) ?: return unreadable()
        if (members.keys != KEYS) return unreadable()
        val state =
            RestoreJournalState.entries.firstOrNull { it.name == members.text(STATE) }
                ?: return unreadable()
        val sha = members.text(EXPECTED_SHA256) ?: return unreadable()
        val changeSeq = members.long(EXPECTED_CHANGE_SEQ) ?: return unreadable()
        val records = members.long(EXPECTED_RECORD_COUNT) ?: return unreadable()
        val hadActive = members.bool(HAD_ACTIVE_DB) ?: return unreadable()
        val safety = members.bool(SAFETY_COPY_OK) ?: return unreadable()
        val verified = members.bool(SHA_VERIFIED) ?: return unreadable()
        if (!SHA256_HEX.matches(sha) || changeSeq < 0 || records < 0) return unreadable()
        return RestoreJournalRead.Present(
            RestoreJournal(state, sha, changeSeq, records, hadActive, safety, verified),
        )
    }

    fun write(journal: RestoreJournal) {
        val json =
            FlatJson.write(
                listOf(
                    STATE to FlatJson.Value.Text(journal.state.name),
                    EXPECTED_SHA256 to FlatJson.Value.Text(journal.expectedSha256),
                    EXPECTED_CHANGE_SEQ to FlatJson.Value.Number(
                        journal.expectedChangeSeq.toString(),
                    ),
                    EXPECTED_RECORD_COUNT to FlatJson.Value.Number(
                        journal.expectedRecordCount.toString(),
                    ),
                    HAD_ACTIVE_DB to FlatJson.Value.Bool(journal.hadActiveDb.toString()),
                    SAFETY_COPY_OK to FlatJson.Value.Bool(journal.safetyCopyOk.toString()),
                    SHA_VERIFIED to FlatJson.Value.Bool(journal.shaVerified.toString()),
                ),
            )
        ops.writeAtomically(files.journal, files.journalTemporary, json.toByteArray(Charsets.UTF_8))
    }

    /** El último paso de un reemplazo terminado o deshecho. */
    fun delete() {
        ops.delete(files.journal)
    }

    /** Aparta un diario ilegible sin tocar ninguna base: soporte lo puede leer después. */
    fun setAsideUnreadable() {
        ops.move(files.journal, files.journalUnreadable, replace = true)
    }

    private fun unreadable() = RestoreJournalRead.Unreadable

    private fun Map<String, FlatJson.Value>.text(key: String): String? =
        (get(key) as? FlatJson.Value.Text)?.text

    private fun Map<String, FlatJson.Value>.long(key: String): Long? {
        val number = get(key) as? FlatJson.Value.Number ?: return null
        // Solo enteros escritos como la app los escribe: sin signo de más, fracción ni exponente.
        return if (DECIMAL.matches(number.text)) number.text.toLongOrNull() else null
    }

    private fun Map<String, FlatJson.Value>.bool(key: String): Boolean? =
        when ((get(key) as? FlatJson.Value.Bool)?.text) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private companion object {
        const val STATE = "state"
        const val EXPECTED_SHA256 = "expected_sha256"
        const val EXPECTED_CHANGE_SEQ = "expected_change_seq"
        const val EXPECTED_RECORD_COUNT = "expected_record_count"
        const val HAD_ACTIVE_DB = "had_active_db"
        const val SAFETY_COPY_OK = "safety_copy_ok"
        const val SHA_VERIFIED = "sha_verified"

        val KEYS =
            setOf(
                STATE,
                EXPECTED_SHA256,
                EXPECTED_CHANGE_SEQ,
                EXPECTED_RECORD_COUNT,
                HAD_ACTIVE_DB,
                SAFETY_COPY_OK,
                SHA_VERIFIED,
            )

        /** Un diario de verdad ocupa unos 250 bytes. */
        const val MAX_BYTES = 4 * 1024

        val DECIMAL = Regex("0|-?[1-9][0-9]*")
    }
}
