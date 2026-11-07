package io.github.yesidrobayo.shopledger.data.backup

/**
 * Decide, con lo que hay en el destino, si se puede subir una copia nueva y con qué generación.
 * Corre antes de generar nada: si no se puede subir, no se copia ni se comprime la base.
 *
 * Es la defensa contra pisar lo que otro teléfono, o una base vieja de este, dejó en el destino. La
 * generación es un entero que crece y decide sola: nunca se comparan relojes.
 */
internal fun interface CopyGuard {
    fun decide(input: GuardInput): GuardDecision
}

/**
 * Lo que la guarda mira.
 *
 * @property copies las copias del destino cuyos metadatos se pudieron leer y coinciden con su
 *   nombre y su tamaño. Las demás no cuentan para decidir.
 * @property deviceId esta instalación.
 * @property headGeneration la generación de la última copia que esta base confirmó, o 0.
 * @property headDeviceId el teléfono de esa copia, o nulo si nunca hubo una.
 * @property pendingGeneration la generación de una subida que quedó sin confirmar, o nula.
 * @property appSchemaVersion la versión del esquema de esta app.
 */
internal data class GuardInput(
    val copies: List<KnownCopy>,
    val deviceId: String,
    val headGeneration: Long,
    val headDeviceId: String?,
    val pendingGeneration: Long?,
    val appSchemaVersion: Int,
)

/** Una copia del destino tal como dicen sus metadatos ya validados. */
internal data class KnownCopy(val generation: Long, val deviceId: String, val schemaVersion: Long)

/** Lo que decidió la guarda. */
internal sealed interface GuardDecision {
    /** Se sube una copia con [generation], mayor que todo lo que esta base escribió. */
    data class Upload(val generation: Long) : GuardDecision

    /**
     * No se sube, y no se escribe nada en el destino ni en el estado: el destino tiene algo que
     * esta guarda no sabe resolver sin preguntar.
     */
    data object Hold : GuardDecision
}

/**
 * La guarda que usa el respaldo mientras no está la que separa cada clase de conflicto y la
 * pregunta a la persona. Solo sube en los casos sin duda, y en cualquier otro se detiene sin tocar
 * nada:
 *
 * - **Sin copias** en el destino, o solo copias más viejas que la cabeza que esta base confirmó:
 *   sube.
 * - **La cabeza del destino es la de esta base,** de la misma generación y del mismo teléfono:
 *   sube la siguiente.
 * - **Cualquier otra cosa** se detiene: una cabeza más nueva, una de otro teléfono con la misma
 *   generación, o una de una versión más nueva de la app.
 *
 * La copia de una subida propia que quedó sin confirmar no cuenta como cabeza: es de esta misma
 * base, y no es más nueva que lo que la base tiene. La generación nueva salta por encima de ella,
 * para que nunca se repita una generación de este teléfono.
 */
internal object HeadOrderGuard : CopyGuard {
    override fun decide(input: GuardInput): GuardDecision {
        val relevant =
            input.copies.filterNot {
                it.deviceId == input.deviceId && it.generation == input.pendingGeneration
            }
        val next = maxOf(input.headGeneration, input.pendingGeneration ?: 0L) + 1
        val top = relevant.maxOfOrNull(KnownCopy::generation) ?: return GuardDecision.Upload(next)
        val head = relevant.filter { it.generation == top }
        return when {
            head.any { it.schemaVersion > input.appSchemaVersion } -> GuardDecision.Hold

            top < input.headGeneration -> GuardDecision.Upload(next)

            top == input.headGeneration && head.all { it.deviceId == input.headDeviceId } ->
                GuardDecision.Upload(next)

            else -> GuardDecision.Hold
        }
    }
}
