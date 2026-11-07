package io.github.yesidrobayo.shopledger.domain.model.backup

/** Cómo están los trabajos de subida en segundo plano, vistos juntos. */
enum class BackupUploadWork {
    /** Ningún trabajo de subida espera ni corre. */
    NONE,

    /** Hay un trabajo de subida encolado, esperando su turno, su red o su retraso. */
    ENQUEUED,

    /** Un trabajo de subida está corriendo ahora. */
    RUNNING,
}
