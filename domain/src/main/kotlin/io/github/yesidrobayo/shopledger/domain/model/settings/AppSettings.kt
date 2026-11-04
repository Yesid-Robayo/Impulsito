package io.github.yesidrobayo.shopledger.domain.model.settings

import kotlin.time.Instant

/**
 * Los ajustes del negocio y de la app, de su única fila.
 *
 * Viajan en la copia, porque son del negocio. Se separan en [profile], cuyos cambios cuentan como
 * un cambio sin respaldar, y [preferences], cuyos cambios no cuentan. [onboardingCompletedAt] es
 * cuándo terminó el primer uso, o `null` si todavía no terminó.
 */
data class AppSettings(
    val profile: BusinessProfile,
    val preferences: AppPreferences,
    val onboardingCompletedAt: Instant?,
    val updatedAt: Instant,
)
