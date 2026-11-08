package io.github.yesidrobayo.shopledger.domain.port

/**
 * El nombre visible de un método de pago inicial, que nace sin nombre propio y se muestra con el
 * texto traducido de su clave.
 *
 * Una venta congela el nombre del método con que cobra. Si el método es inicial y nadie lo
 * renombró, ese nombre es el que la persona ve en ese momento, en el idioma de la interfaz.
 * `:domain` no tiene textos: los da la capa de la interfaz, que implementa este puerto con sus
 * recursos.
 *
 * Responde en memoria, sin tocar el disco ni la red, porque se llama dentro de la transacción de la
 * venta.
 */
fun interface PaymentMethodNames {
    /**
     * El nombre visible del método inicial de clave [systemKey]. Una clave que esta versión no
     * conoce, como la de una copia hecha por una versión más nueva, devuelve la clave tal cual.
     */
    fun systemName(systemKey: String): String
}
