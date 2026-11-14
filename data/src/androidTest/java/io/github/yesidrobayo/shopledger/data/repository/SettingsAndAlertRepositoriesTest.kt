package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
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
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.model.settings.SaleSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los ajustes, las categorías de gasto y las alertas de stock: qué cuenta como cambio del negocio y
 * qué no, y lo que la base nunca deja archivar, borrar ni repetir.
 */
@RunWith(AndroidJUnit4::class)
internal class SettingsAndAlertRepositoriesTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val expenseCategories by lazy { RoomExpenseCategoryRepository(database) }
    private val alerts by lazy { RoomLowStockAlertRepository(database, RestoreLock { false }) }

    @Test
    fun theSettingsStartNeutralAndOnlyTheProfileCountsAsAChange() = runBlocking {
        val initial =
            AppSettings(
                profile = BusinessProfile(
                    businessName = null,
                    defaultTargetMargin = BasisPoints.DEFAULT_TARGET_MARGIN,
                ),
                preferences = AppPreferences(
                    themeMode = ThemeMode.SYSTEM,
                    colorPalette = ColorPalette.INDIGO_MUSTARD,
                    lowStockNotificationsEnabled = true,
                    appLockEnabled = false,
                    appLockTimeout = AppLockTimeout.ONE_MINUTE,
                    meteredBackupPolicy = MeteredBackupPolicy.EVERY_60_MIN,
                    discreetModeEnabled = false,
                    mediaOnMetered = false,
                    updateCheckEnabled = true,
                ),
                onboardingCompletedAt = null,
                updatedAt = AT,
                // La región con que nace la base de prueba, y la moneda de prueba ya bloqueada.
                regional = RegionalSettings(
                    currency = BusinessCurrency(
                        TEST_CURRENCY,
                        decimals = 2,
                        symbol = null,
                        lockedAt = AT,
                    ),
                    cashRounding = Money.ZERO,
                    formatLocaleTag = "es-AR",
                    businessTimeZoneId = "Etc/GMT+3",
                    weekStartDay = DayOfWeek.MONDAY,
                    csvSeparator = CsvSeparator.SEMICOLON,
                    referenceCurrency = null,
                ),
                taxes = TaxSettings(
                    TaxMode.NONE,
                    defaultTaxRateId = null,
                    purchaseTaxRecoverable = false,
                ),
                sales = SaleSettings(defaultRoundingStep = Money.ZERO, tipsEnabled = false),
                loyalty = LoyaltySettings(
                    enabled = false,
                    amountPerPoint = null,
                    pointValue = null,
                ),
                receipt = ReceiptSettings(null, null, null, null),
                notices = NoticeSettings(
                    costStaleDays = 90,
                    expiryNoticeDays = 30,
                    revenueLimit = null,
                    dailySummaryTime = null,
                ),
            )
        assertEquals(initial, settings.get())

        settings.observe().test {
            assertEquals(initial, awaitItem())
            val changes =
                listOf(
                    PreferenceChange.Theme(ThemeMode.DARK),
                    PreferenceChange.Palette(ColorPalette.FOREST_HONEY),
                    PreferenceChange.LowStockNotifications(false),
                    PreferenceChange.AppLock(true),
                    PreferenceChange.AppLockDelay(AppLockTimeout.FIVE_MINUTES),
                    PreferenceChange.MeteredBackup(MeteredBackupPolicy.WIFI_ONLY),
                    PreferenceChange.DiscreetMode(true),
                )
            for (change in changes) settings.updatePreference(change, LATER_AT)
            settings.markOnboardingCompleted(LATER_AT)
            val preferences =
                AppPreferences(
                    ThemeMode.DARK,
                    ColorPalette.FOREST_HONEY,
                    false,
                    true,
                    AppLockTimeout.FIVE_MINUTES,
                    MeteredBackupPolicy.WIFI_ONLY,
                    discreetModeEnabled = true,
                    mediaOnMetered = false,
                    updateCheckEnabled = true,
                )
            awaitValue(
                initial.copy(
                    preferences = preferences,
                    onboardingCompletedAt = LATER_AT,
                    updatedAt = LATER_AT,
                ),
            )
            // Nada de esto es un dato del negocio: no queda pendiente de copia.
            assertEquals(0L, changeSeq())

            commit {
                settings.updateProfile(
                    BusinessProfile("Tienda de ejemplo", BasisPoints(4_000)),
                    LATER_AT,
                )
            }
            assertEquals(
                BusinessProfile("Tienda de ejemplo", BasisPoints(4_000)),
                awaitItem().profile,
            )
            assertEquals(1L, changeSeq())
            cancelAndIgnoreRemainingEvents()
        }
        try {
            settings.updateProfile(BusinessProfile(null, BasisPoints.ZERO), LATER_AT)
            fail("El perfil solo se escribe dentro de la transacción")
        } catch (expected: IllegalStateException) {
            // Esperada.
        }
    }

    @Test
    fun theOptionalGroupsComeBackTogetherOrNotAtAll() = runBlocking {
        execute(
            "UPDATE app_settings SET reference_currency_code = 'XTS', " +
                "reference_currency_decimals = 2, revenue_limit_amount = 1000000, " +
                "revenue_limit_months = 12, daily_summary_time = '20:30', loyalty_enabled = 1, " +
                "loyalty_amount_per_point = 1000, loyalty_point_value = 10 WHERE id = 1",
        )

        val read = settings.get()

        assertEquals(ReferenceCurrency(TEST_CURRENCY, 2), read.regional.referenceCurrency)
        assertEquals(RevenueLimit(Money(1_000_000), 12), read.notices.revenueLimit)
        assertEquals(LocalTime(20, 30), read.notices.dailySummaryTime)
        assertEquals(LoyaltySettings(true, Money(1_000), Money(10)), read.loyalty)
    }

    @Test
    fun theReceiptTemplateIsSavedWithoutTouchingThePortalAddress() = runBlocking {
        execute("UPDATE app_settings SET fiscal_portal_url = '$PORTAL' WHERE id = 1")

        commit {
            settings.updateReceiptTemplate(null, "Calle Mayor 8", "Gracias por venir", LATER_AT)
        }

        val read = settings.get()
        assertEquals(
            ReceiptSettings(null, "Calle Mayor 8", "Gracias por venir", PORTAL),
            read.receipt,
        )
        assertEquals(LATER_AT, read.updatedAt)
        commit { settings.updateReceiptTemplate(null, null, null, LATER_AT) }
        assertEquals(ReceiptSettings(null, null, null, PORTAL), settings.get().receipt)
    }

    @Test
    fun aRejectedPreferenceComesOutTypedWithoutText() = runBlocking {
        // Una guarda propia de esta conexión, que rechaza todo cambio de los ajustes.
        execute(
            "CREATE TEMP TRIGGER reject_settings BEFORE UPDATE ON app_settings BEGIN SELECT RAISE(ABORT, 'guard:test'); END",
        )

        val failure =
            storageFailureOf {
                settings.updatePreference(PreferenceChange.Theme(ThemeMode.LIGHT), LATER_AT)
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(ThemeMode.SYSTEM, settings.get().preferences.themeMode)
    }

    @Test
    fun theSystemExpenseCategoryNeverLeavesAndOneWithExpensesIsOnlyArchived() = runBlocking {
        // La base nace con las categorías iniciales, y la de otros gastos es la del sistema.
        val initial = expenseCategories.observe(ArchiveFilter.ACTIVE).first()
        val other = initial.single { it.isSystem }.id
        val initialIds = initial.map { it.id }

        expenseCategories.observe(ArchiveFilter.ACTIVE).test {
            assertEquals(
                SeedTable.EXPENSE_CATEGORIES.systemKeys,
                awaitItem().map { it.systemKey },
            )
            val details = ExpenseCategoryDetails("Arriendo", "arriendo", isFixed = true)
            val rent = commit { expenseCategories.insert(details, AT) }
            val created =
                ExpenseCategory(
                    rent,
                    null,
                    details,
                    isSystem = false,
                    archivedAt = null,
                    createdAt = AT,
                    updatedAt = AT,
                )
            assertEquals(initialIds + rent, awaitItem().map { it.id })
            assertEquals(created, expenseCategories.findById(rent))
            assertEquals(created, expenseCategories.findActiveByNormalizedName("arriendo"))

            commit { expenseCategories.update(rent, details.copy(isFixed = false), LATER_AT) }
            assertFalse(awaitItem().last().details.isFixed)

            database.expenseDao().insert(expense(rent))
            assertTrue(expenseCategories.hasExpenses(rent))
            commit { expenseCategories.setArchivedAt(rent, LATER_AT, LATER_AT) }
            assertEquals(initialIds, awaitItem().map { it.id })
            assertEquals(
                listOf(rent),
                expenseCategories.observe(ArchiveFilter.ARCHIVED).first().map {
                    it.id
                },
            )
            cancelAndIgnoreRemainingEvents()
        }
        val rent = expenseCategories.observe(ArchiveFilter.ARCHIVED).first().single().id
        val rejected: List<suspend () -> Unit> =
            listOf(
                { expenseCategories.setArchivedAt(other, LATER_AT, LATER_AT) },
                { expenseCategories.delete(other) },
                // Con gastos, su llave impide borrarla: solo se archiva.
                { expenseCategories.delete(rent) },
                {
                    expenseCategories.update(
                        MISSING_ID,
                        ExpenseCategoryDetails("X", "x", false),
                        LATER_AT,
                    )
                },
            )
        for (write in rejected) {
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { write() }
                },
            )
        }
        val unused =
            commit {
                expenseCategories.insert(
                    ExpenseCategoryDetails("Papelería", "papeleria", false),
                    AT,
                )
            }
        assertFalse(expenseCategories.hasExpenses(unused))
        commit { expenseCategories.delete(unused) }
        assertNull(expenseCategories.findById(unused))
    }

    @Test
    fun anAlertIsActiveUntilResolvedAndItsNoticeIsNotAChange() = runBlocking {
        val new =
            NewLowStockAlert(
                productId,
                QuantityMilli(500),
                QuantityMilli(2_000),
                LowStockNotificationStatus.PENDING,
                AT,
                DATE,
            )

        alerts.observeActive().test {
            assertEquals(emptyList<LowStockAlert>(), awaitItem())
            val id = commit { alerts.insert(new) }
            val active =
                LowStockAlert(
                    id,
                    productId,
                    QuantityMilli(500),
                    QuantityMilli(2_000),
                    LowStockNotificationStatus.PENDING,
                    AT,
                    DATE,
                    null,
                )
            assertEquals(listOf(active), awaitItem())
            assertEquals(
                listOf(active),
                alerts.findActiveByProducts(listOf(productId, productId, MISSING_ID)),
            )
            assertEquals(emptyList<LowStockAlert>(), alerts.findActiveByProducts(emptyList()))
            assertEquals(listOf(active), alerts.findPendingNotifications())
            // Una segunda activa del mismo producto: la guarda la rechaza.
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { alerts.insert(new) }
                },
            )

            val seq = changeSeq()
            alerts.setNotificationStatus(id, LowStockNotificationStatus.SHOWN)
            assertEquals(
                listOf(LowStockNotificationStatus.SHOWN),
                awaitItem().map {
                    it.notificationStatus
                },
            )
            assertEquals(emptyList<LowStockAlert>(), alerts.findPendingNotifications())
            assertEquals(seq, changeSeq())
            // Una alerta que ya no existe no tiene aviso que guardar.
            alerts.setNotificationStatus(MISSING_ID, LowStockNotificationStatus.BLOCKED)

            commit { alerts.resolve(id, LATER_AT) }
            assertEquals(emptyList<LowStockAlert>(), awaitItem())
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { alerts.resolve(id, LATER_AT) }
                },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val MISSING_ID = 999L
        val TEST_CURRENCY = requireNotNull(CurrencyCode.of("XTS"))
        const val PORTAL = "https://example.com/portal"
    }
}
