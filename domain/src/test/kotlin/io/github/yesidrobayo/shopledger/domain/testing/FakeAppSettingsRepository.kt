package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.AppPreferences
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.NoticeSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.settings.SaleSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.DayOfWeek

/**
 * Ajustes regionales de prueba, con la zona y la semana por parámetro y una moneda reservada para
 * pruebas: ninguna prueba supone un país.
 */
fun regionalSettings(zoneId: String, weekStartDay: DayOfWeek): RegionalSettings = RegionalSettings(
    currency =
        BusinessCurrency(
            code = requireNotNull(CurrencyCode.of("XTS")),
            decimals = 2,
            symbol = null,
            lockedAt = null,
        ),
    cashRounding = Money(0),
    formatLocaleTag = "und",
    businessTimeZoneId = zoneId,
    weekStartDay = weekStartDay,
    csvSeparator = CsvSeparator.SEMICOLON,
    referenceCurrency = null,
)

/** Unos ajustes completos con [regional]; lo demás son valores iniciales inventados. */
fun appSettings(regional: RegionalSettings): AppSettings = AppSettings(
    profile = BusinessProfile(businessName = null, defaultTargetMargin = BasisPoints(3_000)),
    preferences =
        AppPreferences(
            themeMode = ThemeMode.SYSTEM,
            colorPalette = ColorPalette.INDIGO_MUSTARD,
            lowStockNotificationsEnabled = true,
            appLockEnabled = false,
            appLockTimeout = AppLockTimeout.ONE_MINUTE,
            meteredBackupPolicy = MeteredBackupPolicy.WIFI_ONLY,
            discreetModeEnabled = false,
            mediaOnMetered = false,
            updateCheckEnabled = true,
        ),
    onboardingCompletedAt = null,
    updatedAt = Instant.fromEpochMilliseconds(0),
    regional = regional,
    taxes = TaxSettings(
        mode = TaxMode.NONE,
        defaultTaxRateId = null,
        purchaseTaxRecoverable = false,
    ),
    sales = SaleSettings(defaultRoundingStep = Money(0), tipsEnabled = false),
    loyalty = LoyaltySettings(enabled = false, amountPerPoint = null, pointValue = null),
    receipt =
        ReceiptSettings(
            logoMediaId = null,
            businessDetails = null,
            footerText = null,
            fiscalPortalUrl = null,
        ),
    notices =
        NoticeSettings(
            costStaleDays = 90,
            expiryNoticeDays = 30,
            revenueLimit = null,
            dailySummaryTime = null,
        ),
)

/**
 * Los ajustes en memoria. [settings] se cambia a mano para simular que la persona cambió un ajuste.
 * El bloqueo de la moneda se escribe como lo hace la base, y cada llamada queda en [lockCalls]. La
 * configuración regional se escribe y queda anotada en [regionalWrites]. Las demás escrituras del
 * puerto no hacen falta en estas pruebas y fallan si alguien las usa.
 */
class FakeAppSettingsRepository(initial: AppSettings) : AppSettingsRepository {
    val settings = MutableStateFlow(initial)

    /** Cuántas veces se leyeron los ajustes con [get]. */
    var reads: Int = 0
        private set

    override fun observe(): Flow<AppSettings> = settings

    override suspend fun get(): AppSettings {
        reads += 1
        return settings.value
    }

    /** El instante de cada llamada a [lockCurrency], en orden, haya bloqueado o no. */
    val lockCalls = mutableListOf<Instant>()

    override suspend fun lockCurrency(lockedAt: Instant): Boolean {
        lockCalls += lockedAt
        val current = settings.value
        val currency = current.regional.currency
        // Como la guarda de la base: sin moneda confirmada, el bloqueo se rechaza.
        check(currency.code.isConfirmed) { "La base rechaza bloquear una moneda sin confirmar" }
        if (currency.isLocked) return false
        settings.value =
            current.copy(
                updatedAt = lockedAt,
                regional = current.regional.copy(currency = currency.copy(lockedAt = lockedAt)),
            )
        return true
    }

    /** Cada configuración regional escrita, con su instante, en orden. */
    val regionalWrites = mutableListOf<Pair<RegionalSettingsInput, Instant>>()

    override suspend fun updateRegional(input: RegionalSettingsInput, updatedAt: Instant) {
        regionalWrites += input to updatedAt
        val current = settings.value
        settings.value =
            current.copy(
                updatedAt = updatedAt,
                regional =
                    current.regional.copy(
                        currency =
                            current.regional.currency.copy(
                                code = input.currencyCode,
                                decimals = input.currencyDecimals,
                                symbol = input.currencySymbol,
                            ),
                        cashRounding = input.cashRounding,
                        formatLocaleTag = input.formatLocaleTag,
                        businessTimeZoneId = input.businessTimeZoneId,
                        weekStartDay = input.weekStartDay,
                        csvSeparator = input.csvSeparator,
                    ),
            )
    }

    override suspend fun updateProfile(profile: BusinessProfile, updatedAt: Instant): Unit =
        error("No se usa en estas pruebas")

    /** Cada cambio de las propinas, con su instante, en orden. */
    val tipsWrites = mutableListOf<Pair<Boolean, Instant>>()

    override suspend fun updateTipsEnabled(enabled: Boolean, updatedAt: Instant) {
        tipsWrites += enabled to updatedAt
        val current = settings.value
        settings.value = current.copy(
            updatedAt = updatedAt,
            sales = current.sales.copy(tipsEnabled = enabled),
        )
    }

    override suspend fun updatePreference(change: PreferenceChange, updatedAt: Instant): Unit =
        error("No se usa en estas pruebas")

    override suspend fun markOnboardingCompleted(completedAt: Instant): Unit =
        error("No se usa en estas pruebas")
}
