package io.github.yesidrobayo.shopledger.data.log

/**
 * Valores centinela: datos inventados y fáciles de buscar, que una prueba hace pasar por la app
 * para comprobar que ninguno llega al registro del sistema.
 *
 * Cada tarea que agrega un camino con datos del negocio, como una venta, un respaldo fallido o una
 * restauración rechazada, lo recorre con estos valores dentro de [LogcatCapture.assertClean].
 */
internal object LogSentinels {
    const val PRODUCT = "SENTINEL_PRODUCT_7F3A"
    const val CUSTOMER = "SENTINEL_CUSTOMER_9B2C"
    const val AMOUNT = 987_654_321L
    const val PHONE = "5550142867"
    const val EMAIL = "sentinel.4d1e@example.com"
    const val NOTE = "SENTINEL_NOTE_0D3B"
    const val PASSWORD = "SENTINEL_PASSWORD_E5D0"
    const val WEBDAV_URL = "https://sentinel-8a61.example.com/dav/"
    const val WEBDAV_USER = "sentinel_user_3c47"
    const val SLOT_URI = "content://sentinel.slot.2b9f/document/1"
    const val RECOVERY_CODE = "SENT-7A2B-91CD-E4F6"

    /**
     * El monto también se busca con los separadores de miles y decimales más comunes: un registro
     * que lo formatea seguiría filtrando el dato.
     */
    val amountForms: List<String> =
        listOf(
            "987654321",
            "9876543.21",
            "9876543,21",
            "9.876.543,21",
            "9,876,543.21",
            "9 876 543,21",
            "987.654.321",
            "987,654,321",
        )

    /** Todo lo que nunca debe aparecer en el registro. */
    val all: List<String> =
        listOf(
            PRODUCT,
            CUSTOMER,
            PHONE,
            EMAIL,
            NOTE,
            PASSWORD,
            WEBDAV_URL,
            "sentinel-8a61.example.com",
            WEBDAV_USER,
            SLOT_URI,
            RECOVERY_CODE,
        ) + amountForms

    /**
     * Un mensaje como el de una excepción de SQLite o de una conexión: trae todos los centinelas.
     * Ninguna parte de la app debe registrarlo.
     */
    val leakyMessage: String =
        "INSERT INTO products VALUES ('$PRODUCT', $AMOUNT) cliente=$CUSTOMER tel=$PHONE " +
            "correo=$EMAIL nota=$NOTE en $WEBDAV_URL como $WEBDAV_USER:$PASSWORD " +
            "ranura=$SLOT_URI código=$RECOVERY_CODE"
}
