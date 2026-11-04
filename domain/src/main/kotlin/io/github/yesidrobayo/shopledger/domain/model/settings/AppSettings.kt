package io.github.yesidrobayo.shopledger.domain.model.settings

import kotlin.time.Instant

/**
 * Los ajustes del negocio y de la app, de su única fila.
 *
 * Viajan en la copia, porque son del negocio. Se separan en [profile], cuyos cambios cuentan como
 * un cambio sin respaldar, y [preferences], cuyos cambios no cuentan. [onboardingCompletedAt] es
 * cuándo terminó el primer uso, o `null` si todavía no terminó.
 *
 * Los demás grupos son datos del negocio: [regional] con la moneda, el formato, la zona y la
 * semana; [taxes], [sales], [loyalty], lo que lleva el comprobante en [receipt] y cuándo avisar en
 * [notices].
 */
data class AppSettings(
    val profile: BusinessProfile,
    val preferences: AppPreferences,
    val onboardingCompletedAt: Instant?,
    val updatedAt: Instant,
    val regional: RegionalSettings,
    val taxes: TaxSettings,
    val sales: SaleSettings,
    val loyalty: LoyaltySettings,
    val receipt: ReceiptSettings,
    val notices: NoticeSettings,
)
