package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.ui.backup.BackupWording
import io.github.yesidrobayo.shopledger.ui.components.paletteName
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.rescue.SupportLine
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de Ajustes en el teléfono: el aviso sin bloqueo de pantalla, el respaldo con su
 * destino, el aviso legal, los pasos para borrar, las licencias con su texto completo y los enlaces
 * a los términos y a la política. Sin base, solo lo que no la necesita. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val app = text(R.string.app_name)

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)

    private fun wording(status: BackupStatus, kind: BackupDestinationKind?) = BackupWording(
        BackupOverview(
            report = BackupStatusReport(status, pendingChanges = 0, savingSoon = false),
            destinationKind = kind,
            lastSuccessAt = now,
            failureCode = null,
            staleReason = null,
            meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
            nextMeteredUploadAt = null,
            lastFullReadAt = null,
            computedAt = now,
        ),
        providerLabel = null,
        zone = null,
    )

    private val opened = mutableListOf<AppNavKey>()
    private var termsOpened = 0
    private var privacyOpened = 0

    private val usageActions = mutableListOf<UsageAction>()
    private val profileActions = mutableListOf<ProfileAction>()

    private fun show(
        overview: SettingsOverviewUiState? = null,
        deviceLockMissing: Boolean = false,
        read: suspend (String) -> String? = { "Texto de licencia de muestra" },
        usage: UsageSettingsUiState? = null,
        notificationsBlocked: Boolean = false,
        costStaleDays: Int? = null,
    ) {
        compose.setContent {
            PreviewTheme {
                SettingsEntryScreen(
                    onOpen = { opened += it },
                    onBack = {},
                    tipsEnabled = false,
                    overview = overview,
                    deviceLockMissing = deviceLockMissing,
                    version = "1.0.0 (7)",
                    links =
                        SettingsLinks(
                            onOpen = { opened += it },
                            onOpenTerms = { termsOpened += 1 },
                            onOpenPrivacy = { privacyOpened += 1 },
                        ),
                    readLicense = read,
                    usage = usage,
                    costStaleDays = costStaleDays,
                    onProfileAction = { profileActions += it },
                    notificationsBlocked = notificationsBlocked,
                    onUsageAction = { usageActions += it },
                )
            }
        }
    }

    private fun connected() = SettingsOverviewUiState(
        backup = wording(BackupStatus.UP_TO_DATE, BackupDestinationKind.WEBDAV),
        destination = BackupDestinationInfo.WebDav("cuenta@example.com", "dav.example.com"),
        currency = CurrencyCode.of("EUR"),
    )

    @Test
    fun withoutScreenLockTheRiskNoticeShowsAndBlocksNothing() {
        show(overview = connected(), deviceLockMissing = true)

        compose.onNodeWithText(text(R.string.lock_device_risk)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.language_title)).performScrollTo().performClick()
        assertEquals(listOf<AppNavKey>(AppNavKey.LanguageSettings), opened)
    }

    @Test
    fun withScreenLockThereIsNoNotice() {
        show(overview = connected())

        compose.onNodeWithText(text(R.string.lock_device_risk)).assertDoesNotExist()
    }

    @Test
    fun theBackupGroupShowsTheConnectedAccountAndOpensTheBackupScreen() {
        show(overview = connected())

        compose.onNodeWithText("cuenta@example.com").assertIsDisplayed().performClick()
        compose.onNodeWithText("dav.example.com").assertDoesNotExist()
        assertEquals(listOf<AppNavKey>(AppNavKey.Backup), opened)
    }

    @Test
    fun phoneCloudSlotsShowTheProviderName() {
        show(
            overview =
                connected().copy(
                    backup = wording(BackupStatus.HANDED_OFF, BackupDestinationKind.DOCUMENT_SLOTS),
                    destination = BackupDestinationInfo.Slots("Drive"),
                ),
        )

        compose.onNodeWithText(text(R.string.backup_destination_connected, "Drive"))
            .assertIsDisplayed()
    }

    @Test
    fun withoutDestinationItOffersToChooseOneThroughTheUsualPath() {
        show(
            overview =
                connected().copy(
                    backup = wording(BackupStatus.NOT_CONNECTED, null),
                    destination = null,
                ),
        )

        compose.onNodeWithText(text(R.string.backup_destination_label)).assertDoesNotExist()
        // El aviso y la fila llevan a la misma elección de destino.
        val choose = compose.onAllNodesWithText(text(R.string.backup_action_choose_destination))
        assertEquals(2, choose.fetchSemanticsNodes().size)
        choose[1].performClick()
        assertEquals(listOf<AppNavKey>(AppNavKey.BackupDestinationChoice()), opened)
    }

    @Test
    fun anUnconfirmedCurrencyCarriesItsWarning() {
        show(overview = connected().copy(currency = CurrencyCode.UNCONFIRMED))

        compose.onNodeWithText(text(R.string.region_currency_unconfirmed)).assertExists()
    }

    @Test
    fun theLegalNoticeTheVersionAndTheLinksAreThere() {
        show(overview = connected())

        compose.onNodeWithText(text(R.string.about_not_accounting, app))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.legal_universal_notice, app))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("1.0.0 (7)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.legal_terms_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_privacy)).performScrollTo().performClick()
        assertEquals(1, termsOpened)
        assertEquals(1, privacyOpened)
    }

    @Test
    fun theDeletionStepsOpenInTheirSheet() {
        show(overview = connected())

        compose.onNodeWithText(text(R.string.settings_delete_data)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.delete_guide_before)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete_guide_step_1, app)).assertExists()
        compose.onNodeWithText(text(R.string.delete_guide_step_4, app)).assertExists()
    }

    @Test
    fun eachLicenseOpensItsFullText() {
        show(overview = connected())

        compose.onNodeWithText(text(R.string.settings_licenses)).performScrollTo().performClick()
        val sqlite =
            text(
                R.string.licenses_row,
                text(R.string.license_component_sqlite),
                text(R.string.licenses_public_domain),
            )
        compose.onNodeWithText(sqlite).performScrollTo().performClick()

        compose.onNodeWithText("Texto de licencia de muestra").assertIsDisplayed()
    }

    @Test
    fun anUnreadableLicenseSaysSoWithoutClosing() {
        show(overview = connected(), read = { null })

        compose.onNodeWithText(text(R.string.settings_licenses)).performScrollTo().performClick()
        val figtree =
            text(
                R.string.licenses_row,
                text(R.string.license_component_figtree),
                text(R.string.license_ofl),
            )
        compose.onNodeWithText(figtree).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.state_error_read)).assertIsDisplayed()
    }

    @Test
    fun withoutDatabaseOnlyWhatNeedsNoDataShows() {
        show(overview = null)

        compose.onNodeWithText(text(R.string.backup_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_group_data)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.region_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_licenses)).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theDaysForAnOldCostGoUnderAlertsAndOpenTheirSheet() {
        show(
            overview = connected(),
            usage = UsageSettingsUiState(stockAlerts = true, theme = ThemeMode.SYSTEM),
            costStaleDays = 90,
        )

        compose.onNodeWithText(text(R.string.settings_cost_stale_days)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.settings_cost_stale_days_value, 90, "90"),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_cost_stale_days)).performClick()

        assertEquals(
            listOf<ProfileAction>(ProfileAction.Open(ProfileField.COST_STALE_DAYS)),
            profileActions,
        )
    }

    @Test
    fun zeroDaysSaysTheOldCostIsOff() {
        show(
            overview = connected(),
            usage = UsageSettingsUiState(stockAlerts = true, theme = ThemeMode.SYSTEM),
            costStaleDays = 0,
        )

        compose.onNodeWithText(text(R.string.settings_cost_stale_days_off)).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theStockAlertsSwitchAsksToChangeIt() {
        show(
            overview = connected(),
            usage = UsageSettingsUiState(stockAlerts = false, theme = ThemeMode.SYSTEM),
        )

        compose.onNodeWithText(text(R.string.settings_stock_alerts)).performClick()

        assertEquals(listOf<UsageAction>(UsageAction.StockAlerts(true)), usageActions)
        compose.onNodeWithText(text(R.string.permission_notifications_denied_settings))
            .assertDoesNotExist()
    }

    @Test
    fun blockedAlertsExplainWhyAndLeadToTheSystemSettings() {
        show(
            overview = connected(),
            usage = UsageSettingsUiState(stockAlerts = true, theme = ThemeMode.SYSTEM),
            notificationsBlocked = true,
        )

        compose.onNodeWithText(text(R.string.permission_notifications_denied_settings))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_open_settings)).performClick()
        assertEquals(listOf<UsageAction>(UsageAction.OpenNotificationSettings), usageActions)
    }

    @Test
    fun turnedOffAlertsDoNotComplainAboutTheSystem() {
        show(
            overview = connected(),
            usage = UsageSettingsUiState(stockAlerts = false, theme = ThemeMode.SYSTEM),
            notificationsBlocked = true,
        )

        compose.onNodeWithText(text(R.string.permission_notifications_denied_settings))
            .assertDoesNotExist()
    }

    @Test
    fun theThemeOffersItsThreeModesAndMarksTheChosenOne() {
        show(
            overview = connected(),
            usage = UsageSettingsUiState(stockAlerts = true, theme = ThemeMode.DARK),
        )

        compose.onNodeWithText(text(R.string.settings_theme_title)).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_theme_dark)).assertIsSelected()
        compose.onNodeWithText(text(R.string.settings_theme_system)).assertIsNotSelected()
        compose.onNodeWithText(text(R.string.settings_theme_light)).performClick()
        assertEquals(listOf<UsageAction>(UsageAction.Theme(ThemeMode.LIGHT)), usageActions)
    }

    @Test
    fun unreadablePreferencesSaySoWithRetry() {
        show(overview = connected(), usage = UsageSettingsUiState(readFailed = true))

        compose.onNodeWithText(text(R.string.state_error_read)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_group_appearance)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(listOf<UsageAction>(UsageAction.Retry), usageActions)
    }

    @Test
    fun withoutSettingsNeitherAlertsNorAppearanceShow() {
        show(overview = connected())

        compose.onNodeWithText(text(R.string.settings_group_alerts)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_group_appearance)).assertDoesNotExist()
    }

    /**
     * Dibuja Ajustes dentro del tema con una paleta que cambia al elegirla, como hace la actividad, y
     * con un guardado que puede fallar.
     */
    private fun showWithPalettes(saves: Boolean = true) {
        compose.setContent {
            var palette by remember { mutableStateOf(ColorPalette.INDIGO_MUSTARD) }
            var failed by remember { mutableStateOf(false) }
            AppTheme(palette = palette, darkTheme = false) {
                CompositionLocalProvider(
                    LocalAppFormats provides previewFormats(),
                    LocalDiscreetMode provides false,
                ) {
                    SettingsEntryScreen(
                        onOpen = {},
                        onBack = {},
                        overview = connected(),
                        usage = UsageSettingsUiState(stockAlerts = true, theme = ThemeMode.SYSTEM),
                        onChoosePalette = { chosen ->
                            chosenPalettes += chosen
                            if (saves) palette = chosen else failed = true
                        },
                        paletteFailed = failed,
                        onRetryPalette = { retries += 1 },
                    )
                }
            }
        }
    }

    private val chosenPalettes = mutableListOf<ColorPalette>()
    private var retries = 0

    private fun openColors() {
        compose.onNodeWithText(
            text(R.string.settings_palette_title),
        ).performScrollTo().performClick()
    }

    @Test
    fun theColorsRowNamesThePaletteOnScreen() {
        showWithPalettes()

        compose.onNodeWithText(text(R.string.settings_palette_indigo_mustard)).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theColorsSheetListsTheNinePalettesWithTheChosenOneMarked() {
        showWithPalettes()
        openColors()

        compose.onNodeWithText(text(R.string.settings_palette_help)).assertIsDisplayed()
        for (palette in ColorPalette.entries) {
            compose.onNode(hasText(text(paletteName(palette))) and isSelectable()).assertExists()
        }
        compose.onNode(hasText(text(R.string.settings_palette_default)) and isSelectable())
            .assertIsSelected()
    }

    @Test
    fun choosingAPaletteAppliesItAtOnceAndTheSheetStaysOpen() {
        showWithPalettes()
        openColors()

        compose.onNode(
            hasText(text(R.string.settings_palette_forest_honey)) and isSelectable(),
        ).performScrollTo().performClick()

        assertEquals(listOf(ColorPalette.FOREST_HONEY), chosenPalettes)
        compose.onNode(
            hasText(text(R.string.settings_palette_forest_honey)) and isSelectable(),
        ).assertIsSelected()
        compose.onNode(hasText(text(R.string.settings_palette_default)) and isSelectable())
            .assertIsNotSelected()
        compose.onNodeWithText(text(R.string.settings_palette_help)).assertIsDisplayed()
    }

    @Test
    fun aPaletteThatWasNotSavedSaysSoInsideTheSheet() {
        showWithPalettes(saves = false)
        openColors()

        compose.onNode(
            hasText(text(R.string.settings_palette_plum_coral)) and isSelectable(),
        ).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.settings_palette_error)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.settings_palette_default)) and isSelectable())
            .assertIsSelected()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    private var copies = 0

    private val supportLines =
        listOf(
            SupportLine(text(R.string.support_field_android), "16 (36)"),
            SupportLine(text(R.string.support_field_last_error), "LOCAL_INVARIANT"),
        )

    private fun showWithSupport(overview: SettingsOverviewUiState) {
        compose.setContent {
            PreviewTheme {
                SettingsEntryScreen(
                    onOpen = {},
                    onBack = {},
                    overview = overview,
                    support = supportLines,
                    onCopySupport = { copies += 1 },
                )
            }
        }
    }

    private fun brokenStock(count: Long) = connected().copy(
        backup =
            wording(BackupStatus.FAILED, BackupDestinationKind.WEBDAV).let {
                it.copy(
                    overview =
                        it.overview.copy(
                            failureCode = BackupFailureCode.LOCAL_INVARIANT,
                            invariantFailures = count,
                        ),
                )
            },
    )

    @Test
    fun aBrokenStockCheckSaysHowManyProductsAndLeadsToSupport() {
        showWithSupport(brokenStock(3))

        val notice =
            resources.getQuantityString(R.plurals.backup_failure_local_invariant_body, 3, 3, app)
        compose.onAllNodesWithText(notice)[0].assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.settings_support))[0].performClick()

        compose.onNodeWithText("LOCAL_INVARIANT").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_copy)).performClick()
        assertEquals(1, copies)
    }

    @Test
    fun withoutACountTheStockNoticeDoesNotShow() {
        showWithSupport(brokenStock(0))

        compose.onNodeWithText(text(R.string.support_help)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.settings_support))
            .assertCountEquals(1)
    }

    @Test
    fun theSupportRowOpensTheSheetWithItsLines() {
        showWithSupport(connected())

        compose.onNodeWithText(text(R.string.settings_support)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.support_field_android)).assertIsDisplayed()
        compose.onNodeWithText("16 (36)").assertIsDisplayed()
    }

    @Test
    fun everyBundledLicenseTextIsInTheApk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (entry in LicensedComponent.entries) {
            val textInApk = readLicenseText(context, entry.textAsset)
            assertTrue(entry.name, !textInApk.isNullOrBlank())
        }
    }
}
