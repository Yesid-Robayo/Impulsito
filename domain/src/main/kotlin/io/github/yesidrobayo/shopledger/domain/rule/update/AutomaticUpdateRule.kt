package io.github.yesidrobayo.shopledger.domain.rule.update

import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome

/**
 * Lo que se hace con una búsqueda de versión, la diaria o la que pide la persona. Solo informa:
 * nada de esto descarga, instala ni bloquea algo.
 *
 * - **La versión conocida** cambia solo con un aviso firmado que se pudo leer: una versión nueva
 *   la reemplaza, y saber que la instalada es la más reciente la borra. Sin red, con una firma
 *   que no coincide o con una respuesta rara, queda la de antes: un fallo no inventa ni esconde
 *   una versión.
 * - **La notificación sale una sola vez por versión:** solo con una versión nueva más alta que la
 *   última avisada.
 * - **`Inicio` avisa** mientras la versión conocida sea más alta que la instalada: al instalarla,
 *   el aviso se va solo.
 */
object AutomaticUpdateRule {
    /** La versión conocida tras [outcome], si antes era [previous]. */
    fun known(outcome: UpdateCheckOutcome, previous: KnownUpdate?): KnownUpdate? = when (outcome) {
        is UpdateCheckOutcome.NewVersion ->
            KnownUpdate(outcome.versionCode, outcome.versionName)

        is UpdateCheckOutcome.Latest -> null

        UpdateCheckOutcome.BadSignature,
        UpdateCheckOutcome.Offline,
        UpdateCheckOutcome.Unreadable,
        -> previous
    }

    /**
     * La versión que toca avisar con una notificación tras [outcome], o nula: solo una nueva más
     * alta que [lastNotifiedVersionCode], la última avisada, o cualquiera si nunca se avisó.
     */
    fun toNotify(outcome: UpdateCheckOutcome, lastNotifiedVersionCode: Long?): KnownUpdate? {
        val found = outcome as? UpdateCheckOutcome.NewVersion ?: return null
        if (lastNotifiedVersionCode != null && found.versionCode <= lastNotifiedVersionCode) {
            return null
        }
        return KnownUpdate(found.versionCode, found.versionName)
    }

    /**
     * Lo que dice `Inicio` con la versión conocida [known] y la instalada [installedVersionCode]:
     * la conocida si es más nueva, o nula si no hay nada que avisar.
     */
    fun homeNotice(known: KnownUpdate?, installedVersionCode: Long): KnownUpdate? =
        known?.takeIf { it.versionCode > installedVersionCode }
}
