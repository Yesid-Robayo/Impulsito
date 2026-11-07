package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.work.BackupWorkNames
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * La biblioteca de trabajos, en memoria y con el reloj de la prueba: guarda cada trabajo con la
 * hora a partir de la cual puede correr, aplica reemplazar y conservar como la biblioteca, y anota
 * cada operación para las pruebas que solo miran qué se pidió.
 */
internal class FakeWorkQueue(private val now: () -> Instant) : BackupWorkQueue {
    /** Un trabajo encolado: desde cuándo puede correr, si pide red y por qué corre. */
    data class Queued(val notBefore: Instant, val needsNetwork: Boolean, val kind: RunKind)

    val queued = linkedMapOf<String, Queued>()

    /** Los trabajos que corren ahora. */
    val running = mutableSetOf<String>()

    val calls = mutableListOf<String>()

    override suspend fun restartDebounce(delay: Duration) {
        calls += "debounce $delay"
        queued[BackupWorkNames.DEBOUNCE] = Queued(now() + delay, false, RunKind.AUTOMATIC)
    }

    override suspend fun keepUnmetered() {
        calls += "keep unmetered"
        keep(BackupWorkNames.UPLOAD_UNMETERED, Queued(now(), true, RunKind.AUTOMATIC))
    }

    override suspend fun keepUpload(delay: Duration, needsNetwork: Boolean) {
        calls += "keep upload $delay ${network(needsNetwork)}"
        keep(BackupWorkNames.UPLOAD, Queued(now() + delay, needsNetwork, RunKind.AUTOMATIC))
    }

    override suspend fun replaceUpload(needsNetwork: Boolean, kind: RunKind) {
        calls += "replace upload ${network(needsNetwork)} $kind"
        // Reemplazar cancela también la que corre: el programador nunca lo pide con una corriendo.
        check(BackupWorkNames.UPLOAD !in running) { "Reemplazó una subida que corría" }
        queued[BackupWorkNames.UPLOAD] = Queued(now(), needsNetwork, kind)
    }

    override suspend fun cancel(name: String) {
        calls += "cancel $name"
        queued.remove(name)
    }

    override suspend fun uploadWork(): BackupUploadWork = when {
        BackupWorkNames.UPLOADS.any { it in running } -> BackupUploadWork.RUNNING
        BackupWorkNames.UPLOADS.any { it in queued } -> BackupUploadWork.ENQUEUED
        else -> BackupUploadWork.NONE
    }

    override suspend fun awaitNoneRunning(names: List<String>) {
        calls += "await none running"
    }

    /** Conservar: un trabajo nuevo solo entra si no hay otro con ese nombre encolado o corriendo. */
    private fun keep(name: String, work: Queued) {
        if (name !in queued && name !in running) queued[name] = work
    }

    private fun network(needsNetwork: Boolean) = if (needsNetwork) "con red" else "sin red"
}
