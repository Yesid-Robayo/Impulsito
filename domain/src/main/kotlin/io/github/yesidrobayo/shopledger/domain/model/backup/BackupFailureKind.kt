package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * La clase del último fallo de transporte del respaldo. Se guarda con su nombre en
 * `backup_state.last_failure_kind`, que la guarda de la tabla limita a esta lista, así que los
 * valores no se renombran.
 *
 * Solo informa de qué falló: no detiene nada. Lo que vuelve visible un fallo sostenido es el
 * contador de fallos seguidos, que al llegar a su tope deja el fallo de transporte persistente.
 */
enum class BackupFailureKind {
    /** Red caída, conexión cortada, tiempo agotado, o una subida que no alcanzó a terminar. */
    NETWORK,

    /** El certificado del servicio no se pudo comprobar. No es una red caída. */
    TLS,

    /** El servicio respondió con un error propio. */
    HTTP_5XX,

    /** El servicio pidió esperar, o el recurso estaba bloqueado. */
    RATE_LIMIT,
}
