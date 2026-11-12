package io.github.yesidrobayo.shopledger.data.safety

import android.os.Looper
import android.os.Process
import java.io.File
import kotlin.system.exitProcess

/** Versiones que acompañan cada registro de fallo. */
data class AppVersions(val versionName: String, val versionCode: Long, val sdkInt: Int)

/**
 * Registro local de fallos en `noBackupFilesDir/crash/`: como mucho cinco archivos de 64 KB, con la
 * clase de cada error, la pila ofuscada, las versiones y, desde Android 11, el motivo de salida.
 *
 * Nunca guarda el mensaje de una excepción ni el nombre del hilo: algunas bibliotecas de red le
 * ponen al hilo la URL de la petición. Nada sale de aquí solo: se muestra y se comparte por
 * decisión de la persona.
 *
 * Una caída nativa no pasa por el manejador de Java. Solo queda registrada al arrancar de nuevo,
 * con el motivo de salida que guarda el sistema, así que en Android 10 o anterior no deja rastro
 * aquí.
 *
 * @param versions se evalúa al escribir, no al arrancar, para no pagar la consulta en cada inicio.
 */
internal class CrashRecorder(
    noBackupDir: File,
    private val versions: () -> AppVersions,
    private val currentPid: Int,
    private val clock: () -> Long,
) {
    private val directory = File(noBackupDir, DIRECTORY_NAME)
    private val cursorFile = File(noBackupDir, EXIT_CURSOR_FILE_NAME)

    // El manejador de caídas y el registro de salidas en segundo plano pueden coincidir.
    private val lock = Any()

    /**
     * Al arrancar: poda lo que sobre y, la primera vez, fija desde cuándo cuentan las salidas. Sin
     * esa marca, al instalar o tras borrar los datos se registrarían muertes de una instalación
     * anterior.
     */
    fun prepare() {
        synchronized(lock) {
            safely {
                prune()
                if (!cursorFile.exists()) writeAtomically(cursorFile, "${clock()}\n")
            }
        }
    }

    /** Envuelve el manejador del sistema: primero registra y luego el sistema trata la caída. */
    fun installAsDefaultHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is RecordingHandler) return
        Thread.setDefaultUncaughtExceptionHandler(RecordingHandler(this, previous))
    }

    /** Escribe el registro de una excepción sin capturar. Nunca lanza. */
    fun recordUncaught(onMainThread: Boolean, error: Throwable) {
        synchronized(lock) {
            safely {
                val traceBytes = MAX_FILE_BYTES - EXIT_LINES_RESERVE_BYTES
                val trace = CrashReportFormatter.format(error, traceBytes)
                val text =
                    header(KIND_JAVA_CRASH, currentPid) + "main_thread=$onMainThread\n\n" + trace
                writeAtomically(File(directory, fileName(clock(), currentPid)), text)
                prune()
            }
        }
    }

    /**
     * Agrega los motivos de salida que aún no se registraron. Una caída de Java suma su motivo al
     * archivo que ya escribió ese proceso; una caída nativa o un ANR, que no pasan por el
     * manejador, crean el suyo. Un proceso deja como mucho una entrada con motivo, aunque el
     * sistema registre varias salidas suyas. Las salidas que no son un fallo solo mueven la marca.
     */
    fun recordExits(exits: List<ProcessExit>) {
        synchronized(lock) {
            safely {
                val cursor = readCursor()
                exits
                    .filter { it.timestampMillis > cursor && it.isFailure }
                    .sortedBy { it.timestampMillis }
                    .forEach { recordExit(it) }
                val newest = exits.maxOfOrNull { it.timestampMillis }
                if (newest != null && newest > cursor) writeAtomically(cursorFile, "$newest\n")
                prune()
            }
        }
    }

    /** Todos los registros, del más reciente al más antiguo, para mostrarlos antes de compartir. */
    fun readReport(): String = synchronized(lock) {
        try {
            records()
                .sortedByDescending { timestampOf(it) }
                .joinToString(separator = "\n") { it.readText(Charsets.UTF_8) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun recordExit(exit: ProcessExit) {
        val sameProcess = records().filter { pidOf(it) == exit.pid }
        // Una entrada por proceso: Android 17 registra una segunda salida del mismo proceso al
        // cerrar el aviso de caídas repetidas, y sin esto ocuparía otro de los cinco lugares.
        if (sameProcess.any { it.readText(Charsets.UTF_8).contains(EXIT_REASON_KEY) }) return
        val exitLines =
            "$EXIT_REASON_KEY=${exit.reason}\nexit_status=${exit.status}\n" +
                "exit_importance=${exit.importance}\n"
        val javaRecord =
            sameProcess
                .filter { timestampOf(it) <= exit.timestampMillis }
                .maxByOrNull { timestampOf(it) }
        if (javaRecord != null) {
            writeAtomically(javaRecord, javaRecord.readText(Charsets.UTF_8) + "\n" + exitLines)
        } else {
            // Versiones de ahora: tras una actualización pueden no ser las del proceso que murió.
            val text = header(KIND_EXIT, exit.pid) + exitLines
            writeAtomically(File(directory, fileName(exit.timestampMillis, exit.pid)), text)
        }
    }

    private fun header(kind: String, pid: Int): String {
        // Si la consulta de versiones falla, por ejemplo en una caída muy temprana del arranque,
        // el registro se escribe igual, sin versiones: perderlo entero sería peor.
        val current =
            try {
                versions()
            } catch (_: Exception) {
                null
            }
        return "format=$FORMAT_VERSION\nkind=$kind\n" +
            "app_version_name=${current?.versionName ?: UNKNOWN}\n" +
            "app_version_code=${current?.versionCode ?: UNKNOWN}\n" +
            "android_sdk=${current?.sdkInt ?: UNKNOWN}\npid=$pid\n"
    }

    private fun readCursor(): Long = cursorFile.readText(Charsets.UTF_8).trim().toLongOrNull() ?: 0L

    private fun prune() {
        directory
            .listFiles()
            ?.filter { it.name.endsWith(TEMPORARY_SUFFIX) }
            ?.forEach { it.delete() }
        records()
            .sortedByDescending { timestampOf(it) }
            .drop(MAX_FILES)
            .forEach { it.delete() }
    }

    private fun records(): List<File> = directory
        .listFiles()
        ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
        .orEmpty()

    private fun fileName(timestampMillis: Long, pid: Int): String =
        "$FILE_PREFIX$timestampMillis-$pid$FILE_SUFFIX"

    private fun nameParts(file: File): List<String> = file.name
        .removePrefix(FILE_PREFIX)
        .removeSuffix(FILE_SUFFIX)
        .split('-')

    private fun timestampOf(file: File): Long = nameParts(file).getOrNull(0)?.toLongOrNull() ?: 0L

    private fun pidOf(file: File): Int = nameParts(file).getOrNull(1)?.toIntOrNull() ?: 0

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // El registro de fallos nunca puede ser la causa de otro fallo.
        }
    }

    private class RecordingHandler(
        private val recorder: CrashRecorder,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                recorder.recordUncaught(thread === Looper.getMainLooper().thread, error)
            } catch (_: Throwable) {
                // Ni un error de memoria al registrar impide que el sistema trate la caída.
            } finally {
                // El sistema marca la caída, y así el próximo arranque ve el motivo real.
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(KILLED_STATUS)
                }
            }
        }
    }

    companion object {
        /** Carpeta en `noBackupFilesDir`. */
        const val DIRECTORY_NAME = "crash"

        /** Archivos que se conservan: los más recientes. */
        const val MAX_FILES = 5

        /** Tamaño máximo de cada archivo. */
        const val MAX_FILE_BYTES = 64 * 1024

        private const val FORMAT_VERSION = 1
        private const val KIND_JAVA_CRASH = "java_crash"
        private const val KIND_EXIT = "exit"
        private const val EXIT_REASON_KEY = "exit_reason"
        private const val UNKNOWN = "unknown"

        // Lo que se reserva para sumar el motivo de salida sin pasar de 64 KB.
        private const val EXIT_LINES_RESERVE_BYTES = 1024
        private const val EXIT_CURSOR_FILE_NAME = "crash_exit_cursor"
        private const val FILE_PREFIX = "crash-"
        private const val FILE_SUFFIX = ".txt"
        private const val KILLED_STATUS = 10
    }
}
