package io.github.yesidrobayo.shopledger.ui.region

/**
 * Qué códigos de moneda se ofrecen a la persona al elegir una.
 *
 * La norma ISO 4217 reserva algunos códigos que no son dinero de ningún negocio: el de pruebas,
 * `XTS`; el de sin moneda, `XXX`; los de los metales preciosos; y las unidades de cuenta de
 * organismos, como el derecho especial de giro. ICU los lista como cualquier moneda, así que la
 * lista los quita aquí. No es una lista de países ni de monedas de nadie: son los códigos que la
 * norma marca como especiales, y no cambian con la región del teléfono.
 *
 * Solo deja de ofrecerlos: un negocio que ya guardó uno lo conserva, y las vistas previas y las
 * pruebas siguen usando `XTS`, que es justamente el código para eso.
 */
object OfferedCurrencies {
    /**
     * Los códigos especiales de ISO 4217: pruebas, sin moneda, oro, plata, platino, paladio, el
     * derecho especial de giro, las cuatro unidades del mercado de bonos europeo, el sucre del
     * sistema de compensación regional y la unidad de cuenta del banco de desarrollo africano.
     */
    private val RESERVED: Set<String> = setOf(
        "XTS",
        "XXX",
        "XAU",
        "XAG",
        "XPT",
        "XPD",
        "XDR",
        "XBA",
        "XBB",
        "XBC",
        "XBD",
        "XSU",
        "XUA",
    )

    /** Si [code] se ofrece al elegir una moneda: todo código, salvo los especiales de la norma. */
    fun isOffered(code: String): Boolean = code !in RESERVED
}
