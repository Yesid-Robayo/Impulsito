package io.github.yesidrobayo.shopledger.data.seed

/**
 * Los números al azar del generador de datos de prueba, siempre los mismos para la misma semilla.
 *
 * Es SplitMix64 escrito aquí, y no `kotlin.random.Random` ni `java.util.Random`: su secuencia solo
 * depende de la semilla y de estas líneas, no de la versión de Kotlin, de Java ni de Android. Así
 * la misma semilla da la misma base en cualquier teléfono y con cualquier versión de la app que no
 * toque este archivo. No sirve para nada secreto: solo para inventar datos de prueba.
 */
internal class SeedRandom(seed: Long) {
    private var state = seed

    /** El siguiente valor de 64 bits. Las multiplicaciones desbordan a propósito: es SplitMix64. */
    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var mixed = state
        mixed = (mixed xor (mixed ushr 30)) * MIX_1
        mixed = (mixed xor (mixed ushr 27)) * MIX_2
        return mixed xor (mixed ushr 31)
    }

    /** Un entero de 0 a [bound] − 1. El sesgo del módulo no importa para datos de prueba. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "El tope debe ser positivo" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Un entero de [from] a [to], los dos incluidos. */
    fun between(from: Long, to: Long): Long {
        require(to >= from) { "El rango está al revés" }
        val span = to - from + 1
        return from + (nextLong() ushr 1) % span
    }

    /** Un entero de [from] a [to], los dos incluidos. */
    fun between(from: Int, to: Int): Int = between(from.toLong(), to.toLong()).toInt()

    /** Verdadero con una probabilidad de [perMille] por mil. */
    fun chance(perMille: Int): Boolean = nextInt(PER_MILLE) < perMille

    /** Un elemento cualquiera de [items], que no puede estar vacía. */
    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    /** Un generador nuevo, independiente del actual, que no consume la secuencia de este. */
    fun fork(salt: Long): SeedRandom = SeedRandom(state xor (salt * GOLDEN_GAMMA))

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val MIX_1 = -0x40a7b892e31b1a47L
        const val MIX_2 = -0x6b2fb644ecceee15L
        const val PER_MILLE = 1_000
    }
}
