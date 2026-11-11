package io.github.yesidrobayo.shopledger.domain.usecase.safety

import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.port.PolicyAcceptance
import io.github.yesidrobayo.shopledger.domain.port.PolicyConsentPort

/**
 * La aceptación de la política de privacidad y los términos de uso, para las pantallas: antes de
 * conectar un destino, y cuando cambió la versión que publica la app.
 *
 * No aceptar nunca bloquea nada: solo deja en pausa las copias en la nube.
 */
class PolicyConsentUseCase(private val port: PolicyConsentPort) {
    /** Cómo está la aceptación de las versiones vigentes. */
    suspend fun status(): PolicyConsentStatus = port.status()

    /** Si hay que preguntar antes de conectar un destino: sin la versión vigente aceptada. */
    suspend fun needsAcceptance(): Boolean = when (port.status()) {
        PolicyConsentStatus.ACCEPTED -> false
        PolicyConsentStatus.NEVER_ACCEPTED, PolicyConsentStatus.OUTDATED -> true
    }

    /**
     * Si hay que mostrar al abrir la app que cambió el uso de los datos: solo si la persona aceptó
     * una versión anterior. Quien nunca aceptó lo ve al conectar un destino.
     */
    suspend fun changedSinceAccepted(): Boolean = port.status() == PolicyConsentStatus.OUTDATED

    /**
     * La persona aceptó las versiones vigentes. Devuelve si quedó guardado: si el teléfono no pudo
     * guardarlo, nada cambió y se puede intentar otra vez.
     */
    suspend fun accept(): Boolean = when (port.acceptCurrent()) {
        PolicyAcceptance.SAVED -> true
        PolicyAcceptance.NOT_SAVED -> false
    }
}
