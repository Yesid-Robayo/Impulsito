package io.github.yesidrobayo.shopledger.domain.rule.safety

import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions

/**
 * Decide si lo que aceptó la persona cubre lo que publica la app. Los dos textos se comparan por
 * separado: una política nueva pide aceptar aunque los términos no hayan cambiado, y al revés.
 *
 * Una versión aceptada más nueva que la vigente cuenta como aceptada. Pasa al volver a una
 * compilación anterior de la app, y entonces la persona ya aceptó un uso que esa compilación no
 * amplía: pausar sus copias por eso la dejaría sin respaldo sin ninguna razón.
 */
object PolicyConsentRule {
    /** Cómo está la aceptación, con lo que la persona aceptó, [accepted], o nulo si nunca aceptó. */
    fun status(accepted: PolicyVersions?, current: PolicyVersions): PolicyConsentStatus = when {
        accepted == null -> PolicyConsentStatus.NEVER_ACCEPTED

        accepted.privacyPolicy < current.privacyPolicy ||
            accepted.termsOfUse < current.termsOfUse -> PolicyConsentStatus.OUTDATED

        else -> PolicyConsentStatus.ACCEPTED
    }
}
