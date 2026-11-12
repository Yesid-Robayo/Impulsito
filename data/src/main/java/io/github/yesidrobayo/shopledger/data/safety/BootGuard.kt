package io.github.yesidrobayo.shopledger.data.safety

import android.content.Context
import android.os.Build
import android.os.Process

/**
 * El modo seguro y el registro local de fallos, armados para el proceso de la app. Junta el conteo
 * de arranques, el registro de caídas y los motivos de salida del sistema, y los llama en los
 * cuatro momentos del arranque:
 *
 * 1. [start], en `Application.attachBaseContext`: lee el conteo y decide el modo seguro; si no,
 *    anota un intento. Instala el registro de caídas, para que una caída desde ahí deje su archivo.
 * 2. [afterApplicationCreated], en la última línea de `Application.onCreate`: el proceso arrancó
 *    bien, y su intento deja de contar. Un proceso que el sistema abre para un trabajo nunca cuenta.
 * 3. [onActivityCreated], en la primera línea de la actividad: anota otro intento.
 * 4. [onStable], tras diez segundos estables o al dejar de verse la pantalla: vuelve a cero.
 *
 * En modo seguro no se anota ni se vacía nada: salir con Atrás no lo apaga. Solo [retry], que es
 * `Reintentar abrir`, lo vacía. Todo es de `noBackupFilesDir`, sin la base: el modo seguro existe
 * justo para cuando algo del arranque cierra la app.
 *
 * Todas las llamadas son del hilo principal y cortas. Ninguna falla del disco detiene el arranque.
 */
class BootGuard private constructor(
    private val attempts: BootAttemptStore,
    private val crashes: CrashRecorder,
    private val exits: ExitReasonSource,
    /** Lo que decidió el primer paso, solo con números. */
    val report: BootReport,
) {
    /** Si este proceso abrió en modo seguro. Solo [retry] lo apaga. */
    val safeMode: Boolean get() = attempts.isSafeMode

    /**
     * Paso 2. Registra los motivos de salida que el sistema guardó de procesos anteriores, para la
     * información para soporte, y da por bueno el arranque de este proceso.
     *
     * En modo seguro registra ya, porque la pantalla de rescate los muestra. Si no, en otro hilo:
     * preguntarle al sistema no debe demorar la primera pantalla.
     */
    fun afterApplicationCreated() {
        if (safeMode) {
            crashes.recordExits(exits.recentExits())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val task = Runnable { crashes.recordExits(exits.recentExits()) }
            Thread(task, EXIT_RECORDER_THREAD).start()
        }
        attempts.onApplicationCreated()
    }

    /** Paso 3: se abre la pantalla. Hasta quedar estable, una caída cuenta como fallo. */
    fun onActivityCreated() = attempts.onActivityCreated()

    /** Paso 4: la pantalla quedó estable, o dejó de verse. El conteo vuelve a cero. */
    fun onStable() = attempts.onStable()

    /** `Reintentar abrir`: vacía el conteo para que el arranque siguiente abra normal. */
    fun retry() = attempts.resetForRetry()

    /** El registro de fallos, del más reciente al más antiguo, para mostrarlo antes de compartirlo. */
    fun crashReport(): String = crashes.readReport()

    companion object {
        /** Cuánto debe quedar abierta la pantalla para que el arranque cuente como bueno. */
        const val STABLE_AFTER_MILLIS: Long = BootAttemptStore.STABLE_AFTER_MILLIS

        private const val EXIT_RECORDER_THREAD = "exit-recorder"

        /**
         * Paso 1, con el contexto base de `Application.attachBaseContext`, donde todavía no hay
         * `applicationContext`: solo se usan archivos de `noBackupFilesDir` y servicios del sistema.
         *
         * @param versions las versiones de la app y de Android, que se piden solo al escribir un
         *   registro de fallo, no al arrancar.
         */
        fun start(base: Context, versions: () -> AppVersions): BootGuard {
            val noBackup = base.noBackupFilesDir
            val pid = Process.myPid()
            val clock = System::currentTimeMillis
            val exits = ExitReasonReader(base)
            val attempts = BootAttemptStore(noBackup, exits, pid, clock)
            val report = attempts.onApplicationStart()
            val crashes = CrashRecorder(noBackup, versions, pid, clock)
            crashes.prepare()
            crashes.installAsDefaultHandler()
            return BootGuard(attempts, crashes, exits, report)
        }
    }
}
