package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.settings.AppSettingsEntity
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
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
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.model.settings.SaleSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

/**
 * Los ajustes sobre la única fila de `app_settings`, que nace con la base.
 *
 * El perfil del negocio se escribe dentro de la transacción del caso de uso y cuenta como cambio.
 * Cada preferencia y el fin del primer uso se escriben solos, con una sentencia que toca solo su
 * columna: no cuentan como cambio, y dos pantallas que cambian dos preferencias no se pisan. Como
 * toda escritura, no entran mientras se restaura la base.
 *
 * Sin la fila, la base está rota: nada aquí la crea ni la inventa.
 */
internal class RoomAppSettingsRepository(database: AppDatabase, restoreLock: RestoreLock) :
    AppSettingsRepository {
    private val settings = database.appSettingsDao()
    private val moneyAccounts = database.moneyAccountDao()
    private val writes = RepositoryWrites(database)
    private val standalone = StandaloneWrites(restoreLock)

    override fun observe(): Flow<AppSettings> =
        settings.observe().map { toSettings(it ?: brokenRow()) }.distinctUntilChanged()

    override suspend fun get(): AppSettings = toSettings(settings.get() ?: brokenRow())

    override suspend fun updateProfile(profile: BusinessProfile, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                settings.updateProfile(
                    businessName = profile.businessName,
                    defaultTargetMarginBp = profile.defaultTargetMargin.bp,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun lockCurrency(lockedAt: Instant): Boolean = writes.inTransaction {
        // Solo la primera vez: la sentencia no toca una moneda ya bloqueada y devuelve 0.
        settings.lockCurrency(lockedAt.toStoredMillis()) == 1
    }

    override suspend fun updateTipsEnabled(enabled: Boolean, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                settings.updateTipsEnabled(if (enabled) 1L else 0L, updatedAt.toStoredMillis()),
            )
        }
    }

    override suspend fun updateCostStaleDays(days: Int, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(settings.updateCostStaleDays(days.toLong(), updatedAt.toStoredMillis()))
        }
    }

    override suspend fun updateExpiryNoticeDays(days: Int, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                settings.updateExpiryNoticeDays(days.toLong(), updatedAt.toStoredMillis()),
            )
        }
    }

    override suspend fun updateReceiptTemplate(
        logoMediaId: Long?,
        businessDetails: String?,
        footerText: String?,
        updatedAt: Instant,
    ) {
        writes.inTransaction {
            requireOneRow(
                settings.updateReceiptTemplate(
                    logoMediaId = logoMediaId,
                    businessDetails = businessDetails,
                    footerText = footerText,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun updateReferenceCurrency(
        reference: ReferenceCurrency?,
        updatedAt: Instant,
    ) {
        writes.inTransaction {
            requireOneRow(
                settings.updateReferenceCurrency(
                    code = reference?.code?.code,
                    decimals = reference?.decimals?.toLong(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun updateLoyalty(loyalty: LoyaltySettings, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                settings.updateLoyalty(
                    enabled = if (loyalty.enabled) 1L else 0L,
                    amountPerPoint = loyalty.amountPerPoint?.minor,
                    pointValue = loyalty.pointValue?.minor,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun updateRegional(input: RegionalSettingsInput, updatedAt: Instant) {
        writes.inTransaction {
            val at = updatedAt.toStoredMillis()
            val unlocked = settings.get()?.currencyLockedAt == null
            requireOneRow(
                settings.updateRegional(
                    currencyCode = input.currencyCode.code,
                    currencyDecimals = input.currencyDecimals.toLong(),
                    currencySymbol = input.currencySymbol,
                    cashRounding = input.cashRounding.minor,
                    formatLocale = input.formatLocaleTag,
                    zoneId = input.businessTimeZoneId,
                    weekStartDay = input.weekStartDay.isoDayNumber.toLong(),
                    csvSeparator = input.csvSeparator.name,
                    updatedAt = at,
                ),
            )
            // La cuenta de efectivo inicial nace sin moneda, como el negocio, y la sigue hasta el
            // bloqueo. Después ya no cambia: tiene movimientos, y la moneda tampoco cambia.
            val cash = moneyAccounts.findBySystemKey(INITIAL_CASH_ACCOUNT)
            if (unlocked &&
                cash != null &&
                (
                    cash.currencyCode != input.currencyCode.code ||
                        cash.currencyDecimals != input.currencyDecimals.toLong()
                    )
            ) {
                requireOneRow(
                    moneyAccounts.setCurrency(
                        id = cash.id,
                        currencyCode = input.currencyCode.code,
                        currencyDecimals = input.currencyDecimals.toLong(),
                        updatedAt = at,
                    ),
                )
            }
        }
    }

    override suspend fun updatePreference(change: PreferenceChange, updatedAt: Instant) {
        val at = updatedAt.toStoredMillis()
        val changed =
            standalone.write {
                when (change) {
                    is PreferenceChange.Theme -> settings.updateThemeMode(change.mode.name, at)

                    is PreferenceChange.Palette -> settings.updateColorPalette(
                        change.palette.name,
                        at,
                    )

                    is PreferenceChange.LowStockNotifications ->
                        settings.updateLowStockNotificationsEnabled(
                            change.enabled.toStoredFlag(),
                            at,
                        )

                    is PreferenceChange.AppLock ->
                        settings.updateAppLockEnabled(change.enabled.toStoredFlag(), at)

                    is PreferenceChange.AppLockDelay ->
                        settings.updateAppLockTimeoutSeconds(change.timeout.seconds.toLong(), at)

                    is PreferenceChange.MeteredBackup ->
                        settings.updateMeteredBackupPolicy(change.policy.name, at)

                    is PreferenceChange.DiscreetMode ->
                        settings.updateDiscreetModeEnabled(change.enabled.toStoredFlag(), at)
                }
            }
        requireOneRow(changed)
    }

    override suspend fun markOnboardingCompleted(completedAt: Instant) {
        requireOneRow(
            standalone.write {
                settings.markOnboardingCompleted(completedAt.toStoredMillis())
            },
        )
    }

    private fun toSettings(row: AppSettingsEntity): AppSettings = AppSettings(
        profile = BusinessProfile(
            businessName = row.businessName,
            defaultTargetMargin = BasisPoints(row.defaultTargetMarginBp),
        ),
        preferences = AppPreferences(
            themeMode = storedEnum<ThemeMode>(row.themeMode),
            colorPalette = storedEnum<ColorPalette>(row.colorPalette),
            lowStockNotificationsEnabled = row.lowStockNotificationsEnabled.toFlag(),
            appLockEnabled = row.appLockEnabled.toFlag(),
            appLockTimeout =
                AppLockTimeout.ofSeconds(row.appLockTimeoutSeconds.toStoredInt()) ?: brokenRow(),
            meteredBackupPolicy = storedEnum<MeteredBackupPolicy>(row.meteredBackupPolicy),
            discreetModeEnabled = row.discreetModeEnabled.toFlag(),
            mediaOnMetered = row.mediaOnMetered.toFlag(),
            updateCheckEnabled = row.updateCheckEnabled.toFlag(),
        ),
        onboardingCompletedAt = row.onboardingCompletedAt?.toInstant(),
        updatedAt = row.updatedAt.toInstant(),
        regional = regionalOf(row),
        taxes = TaxSettings(
            mode = storedEnum<TaxMode>(row.taxMode),
            defaultTaxRateId = row.defaultTaxRateId,
            purchaseTaxRecoverable = row.purchaseTaxRecoverable.toFlag(),
        ),
        sales = SaleSettings(
            defaultRoundingStep = Money(row.defaultRoundingStep),
            tipsEnabled = row.tipsEnabled.toFlag(),
        ),
        loyalty = LoyaltySettings(
            enabled = row.loyaltyEnabled.toFlag(),
            amountPerPoint = row.loyaltyAmountPerPoint?.let(::Money),
            pointValue = row.loyaltyPointValue?.let(::Money),
        ),
        receipt = ReceiptSettings(
            logoMediaId = row.receiptLogoMediaId,
            businessDetails = row.receiptBusinessDetails,
            footerText = row.receiptFooterText,
            fiscalPortalUrl = row.fiscalPortalUrl,
        ),
        notices = NoticeSettings(
            costStaleDays = row.costStaleDays.toStoredInt(),
            expiryNoticeDays = row.expiryNoticeDays.toStoredInt(),
            revenueLimit = revenueLimitOf(row.revenueLimitAmount, row.revenueLimitMonths),
            dailySummaryTime = row.dailySummaryTime?.let(::storedTime),
        ),
    )

    /** La moneda, el formato, la zona y la semana del negocio, como se guardaron. */
    private fun regionalOf(row: AppSettingsEntity): RegionalSettings = RegionalSettings(
        currency = BusinessCurrency(
            code = CurrencyCode.of(row.currencyCode) ?: brokenRow(),
            decimals = row.currencyDecimals.toStoredInt(),
            symbol = row.currencySymbol,
            lockedAt = row.currencyLockedAt?.toInstant(),
        ),
        cashRounding = Money(row.cashRounding),
        formatLocaleTag = row.formatLocale,
        businessTimeZoneId = row.businessTimeZone,
        weekStartDay = storedWeekDay(row.weekStartDay),
        csvSeparator = storedEnum<CsvSeparator>(row.csvSeparator),
        referenceCurrency = referenceCurrencyOf(
            row.referenceCurrencyCode,
            row.referenceCurrencyDecimals,
        ),
    )

    /** La moneda de referencia: el código y sus decimales van juntos, o ninguno. */
    private fun referenceCurrencyOf(code: String?, decimals: Long?): ReferenceCurrency? = when {
        code == null && decimals == null -> null
        code == null || decimals == null -> brokenRow()
        else -> ReferenceCurrency(CurrencyCode.of(code) ?: brokenRow(), decimals.toStoredInt())
    }

    /** El tope de ingresos: el monto y los meses van juntos, o ninguno. */
    private fun revenueLimitOf(amount: Long?, months: Long?): RevenueLimit? = when {
        amount == null && months == null -> null
        amount == null || months == null -> brokenRow()
        else -> RevenueLimit(Money(amount), months.toStoredInt())
    }

    /** El día con que empieza la semana, de 1, lunes, a 7, domingo, como en ISO 8601. */
    private fun storedWeekDay(isoDay: Long): DayOfWeek =
        DayOfWeek.entries.firstOrNull { it.isoDayNumber.toLong() == isoDay } ?: brokenRow()

    /** La hora guardada como `HH:mm`. */
    private fun storedTime(text: String): LocalTime = if (STORED_TIME.matches(text)) {
        LocalTime.Formats.ISO.parseOrNull(text) ?: brokenRow()
    } else {
        brokenRow()
    }

    private companion object {
        val STORED_TIME = Regex("[0-9]{2}:[0-9]{2}")

        /** La clave de la cuenta de efectivo con que nace el negocio. */
        const val INITIAL_CASH_ACCOUNT = "CASH"
    }
}
