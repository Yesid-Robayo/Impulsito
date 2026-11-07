package io.github.yesidrobayo.shopledger.data.safety

import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.PolicyAcceptance
import io.github.yesidrobayo.shopledger.domain.port.PolicyConsentPort
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.domain.rule.safety.PolicyConsentRule
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * La compuerta de la política: sin la versión vigente aceptada, ninguna copia sale a la nube. El
 * trabajo de subida termina sin hacer nada, el programador no encola nada que vaya al destino, y el
 * estado del respaldo lo dice. Las ventas, exportar y las copias locales no la miran: no aceptar
 * nunca bloquea los datos.
 *
 * Lee el disco cada vez que se le pregunta, sin guardar nada en memoria: así ningún proceso, como
 * el de un trabajo en segundo plano, se queda con una respuesta vieja.
 *
 * @param file el archivo de la aceptación.
 * @param current las versiones que publica esta compilación.
 * @param onAccepted lo que sigue a aceptar: volver a programar las copias que esperaban. Corre
 *   después de que la aceptación quedó en el disco.
 */
internal class PolicyConsentGate(
    private val file: PolicyConsentFile,
    private val current: PolicyVersions,
    private val log: EventLog,
    private val onAccepted: suspend () -> Unit,
) : PolicyConsentPort {
    private val acceptances = MutableStateFlow(0L)

    /**
     * Emite al empezar a mirarlo y cada vez que la persona acepta, para que el estado del respaldo
     * se calcule otra vez.
     */
    val changes: Flow<Unit> = acceptances.map {}

    /**
     * Cómo está la aceptación. Un archivo que no se entiende cuenta como una aceptación vieja: había
     * algo aceptado, ya no se sabe qué, y se pregunta otra vez.
     */
    override suspend fun status(): PolicyConsentStatus = when (val load = file.load()) {
        ConsentLoad.Missing -> PolicyConsentStatus.NEVER_ACCEPTED
        ConsentLoad.Unreadable -> PolicyConsentStatus.OUTDATED
        is ConsentLoad.Accepted -> PolicyConsentRule.status(load.versions, current)
    }

    /** Si las copias pueden salir a la nube: la persona aceptó la versión vigente. */
    suspend fun allowsUploads(): Boolean = status() == PolicyConsentStatus.ACCEPTED

    override suspend fun acceptCurrent(): PolicyAcceptance {
        try {
            file.save(current)
        } catch (failure: IOException) {
            log.warn(DataEvent.POLICY_CONSENT_NOT_SAVED, failure)
            return PolicyAcceptance.NOT_SAVED
        }
        log.info(DataEvent.POLICY_CONSENT_SAVED)
        acceptances.update { it + 1 }
        onAccepted()
        return PolicyAcceptance.SAVED
    }
}
