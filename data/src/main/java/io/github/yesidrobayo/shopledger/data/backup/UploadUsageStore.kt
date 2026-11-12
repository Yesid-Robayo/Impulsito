package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.destination.FlatJson
import io.github.yesidrobayo.shopledger.data.destination.UploadUsageMeter
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadUsage
import io.github.yesidrobayo.shopledger.domain.rule.backup.UploadUsageRule
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Los bytes que la app subió a WebDAV en el mes, en `noBackupFilesDir/upload-usage.json`: el mes y
 * los bytes, `{"month":"2026-11","bytes":84000000}`. Pertenece a la instalación, no a los datos, y
 * no viaja en una copia. No guarda nada del negocio.
 *
 * Se escribe con temporal, sincronización y renombrado. Un archivo que no se entiende se lee como
 * ausente: el contador vuelve a cero, que solo subestima lo gastado, nunca detiene una copia.
 *
 * @param file la ruta del archivo, en `noBackupFilesDir`. Se pide al leer o escribir, en
 *   [ioContext]: pedir esa carpeta toca el disco, porque Android la crea si falta.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class UploadUsageStore(
    private val file: () -> File,
    private val ioContext: CoroutineContext,
) {
    // Leer, sumar y escribir va junto: dos subidas que terminan a la vez no se pisan la suma.
    private val mutex = Mutex()

    /** Lo guardado, o nulo si no hay nada o el archivo no se entiende. */
    suspend fun load(): UploadUsage? = withContext(ioContext) { read() }

    /** Suma [bytes] enviados en [month]. Lanza `IOException` si no quedó en el disco. */
    suspend fun add(month: String, bytes: Long) {
        if (bytes <= 0) return
        withContext(ioContext) {
            mutex.withLock {
                val next = UploadUsageRule.add(read(), month, bytes)
                val json =
                    FlatJson.write(
                        listOf(
                            MONTH to FlatJson.Value.Text(next.month),
                            BYTES to FlatJson.Value.Number(next.bytes.toString()),
                        ),
                    )
                DurableFile.write(file(), json.encodeToByteArray())
            }
        }
    }

    private fun read(): UploadUsage? {
        val bytes =
            try {
                file().readBytes()
            } catch (_: FileNotFoundException) {
                return null
            } catch (_: IOException) {
                return null
            }
        if (bytes.size > MAX_BYTES) return null
        val members = FlatJson.parse(bytes.decodeToString()) ?: return null
        if (members.keys != setOf(MONTH, BYTES)) return null
        val month = (members[MONTH] as? FlatJson.Value.Text)?.text ?: return null
        val count =
            (members[BYTES] as? FlatJson.Value.Number)?.text?.takeIf(COUNT::matches)?.toLongOrNull()
                ?: return null
        if (!UploadUsage.MONTH.matches(month)) return null
        return UploadUsage(month, count)
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "upload-usage.json"

        private const val MONTH = "month"
        private const val BYTES = "bytes"

        /** Un objeto de dos campos cortos cabe de sobra. */
        private const val MAX_BYTES = 128

        /** Un número de bytes, sin signo ni ceros a la izquierda. */
        private val COUNT = Regex("0|[1-9][0-9]{0,18}")
    }
}

/**
 * Anota en [store] lo que sube WebDAV, en el mes de [month]. Sin mes, porque la zona del negocio no
 * se pudo leer, suma al mes ya guardado; sin nada guardado no anota: el número nunca se inventa un
 * mes. Una falla del disco solo se registra con su clase: contar nunca detiene una copia.
 *
 * @param month el mes de ahora en la zona del negocio, como `AAAA-MM`, o nulo si no se sabe.
 * @param onFailure recibe la falla al escribir, para registrarla sin su mensaje.
 */
internal class UploadUsageRecorder(
    private val store: UploadUsageStore,
    private val month: suspend () -> String?,
    private val onFailure: (Exception) -> Unit,
) : UploadUsageMeter {
    override suspend fun sent(bytes: Long) {
        try {
            val current = month() ?: store.load()?.month ?: return
            store.add(current, bytes)
        } catch (failure: IOException) {
            onFailure(failure)
        }
    }
}
