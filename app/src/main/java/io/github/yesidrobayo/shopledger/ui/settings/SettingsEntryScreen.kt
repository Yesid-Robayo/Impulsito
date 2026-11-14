package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.localization.UiLanguage
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.ui.backup.BackupWording
import io.github.yesidrobayo.shopledger.ui.backup.backupBannerLook
import io.github.yesidrobayo.shopledger.ui.backup.backupChipLook
import io.github.yesidrobayo.shopledger.ui.backup.invariantBody
import io.github.yesidrobayo.shopledger.ui.backup.needsBanner
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusBanner
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusChip
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.i18n.ownName
import io.github.yesidrobayo.shopledger.ui.rescue.SupportInfoSheet
import io.github.yesidrobayo.shopledger.ui.rescue.SupportLine
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Lo que Ajustes pide fuera de sí: abrir otra pantalla, revisar la política, los términos y la
 * política de privacidad. Sin base, solo funcionan las que no la necesitan.
 */
data class SettingsLinks(
    val onOpen: (AppNavKey) -> Unit,
    val onReviewPolicy: () -> Unit = {},
    val onOpenTerms: () -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
)

/** Las hojas de `Ayuda y acerca de` que se abren dentro de Ajustes. */
enum class AboutSheet { LICENSES, DELETE_GUIDE, SUPPORT }

/**
 * Ajustes: solo configuración, en grupos y de arriba abajo.
 *
 * 1. Un aviso si el teléfono no tiene bloqueo de pantalla: no bloquea nada, solo explica el riesgo.
 * 2. `Respaldo`: el estado y dónde se guardan las copias, que abre la pantalla del respaldo. Sin
 *    destino, ofrece elegir dónde guardar, por el mismo camino de siempre.
 * 3. `Avisos`: el interruptor de los avisos de stock.
 * 4. `Perfil del negocio`, `Región e idioma` con su valor actual, `Ventas y cobro`, `Seguridad` y
 *    `Apariencia`, con el tema.
 * 5. `Datos del negocio`: las copias locales y los pasos para borrar los datos a mano.
 * 6. `Ayuda y acerca de`: versión, el aviso de que no reemplaza la contabilidad, las licencias, los
 *    términos y la política de privacidad.
 *
 * Los grupos que leen la base no se dibujan sin ella; región, idioma y lo de `Acerca de` sí.
 *
 * @param profile el perfil del negocio, o `null` mientras se lee o sin base: el grupo no se dibuja.
 * @param profileSheet la hoja de un dato del perfil, si está abierta.
 * @param tipsEnabled si las propinas están encendidas, o `null` mientras se lee: la fila no se
 *   dibuja hasta saberlo.
 * @param tipsSaveFailed si el último cambio de las propinas no se pudo guardar.
 * @param security el grupo `Seguridad`, con el bloqueo de la app, o `null` sin base.
 * @param overview el respaldo, la moneda y el idioma, o `null` sin base.
 * @param deviceLockMissing el teléfono no tiene bloqueo de pantalla.
 * @param version la versión y la compilación, o `null` mientras se leen.
 * @param readLicense lee el texto de una licencia, fuera del hilo principal.
 * @param csvExport `Exportar a CSV`, en `Datos del negocio`, o `null` sin base.
 * @param usage los avisos de stock y el tema, o `null` sin base.
 * @param costStaleDays los días para costo viejo, en `Avisos`, o `null` mientras se leen.
 * @param notificationsBlocked los avisos no pueden salir del teléfono: sin permiso, apagados o con
 *   el canal silenciado.
 * @param onChoosePalette aplica y guarda una paleta; la hoja de colores sigue abierta.
 * @param paletteFailed la última paleta elegida no se guardó, y volvió la anterior.
 * @param onRetryPalette vuelve a elegir la paleta que no se guardó.
 * @param support las líneas de `Información para soporte`, sin datos del negocio.
 * @param onCopySupport copia esas líneas al portapapeles.
 */
@Composable
fun SettingsEntryScreen(
    onOpen: (AppNavKey) -> Unit,
    onBack: () -> Unit,
    tipsEnabled: Boolean? = null,
    onTipsChange: (Boolean) -> Unit = {},
    tipsSaveFailed: Boolean = false,
    profile: BusinessProfile? = null,
    profileSheet: ProfileSheet? = null,
    onProfileAction: (ProfileAction) -> Unit = {},
    security: SecuritySettingsUiState? = null,
    onSecurityAction: (SecurityAction) -> Unit = {},
    overview: SettingsOverviewUiState? = null,
    deviceLockMissing: Boolean = false,
    version: String? = null,
    links: SettingsLinks = SettingsLinks(onOpen),
    readLicense: suspend (String) -> String? = { null },
    csvExport: CsvExportUiState? = null,
    onCsvExportAction: (CsvExportAction) -> Unit = {},
    usage: UsageSettingsUiState? = null,
    costStaleDays: Int? = null,
    notificationsBlocked: Boolean = false,
    onUsageAction: (UsageAction) -> Unit = {},
    onChoosePalette: (ColorPalette) -> Unit = {},
    paletteFailed: Boolean = false,
    onRetryPalette: () -> Unit = {},
    support: List<SupportLine> = emptyList(),
    onCopySupport: () -> Unit = {},
    readFailed: Boolean = false,
    onRetryRead: () -> Unit = {},
) {
    var aboutSheet by rememberSaveable { mutableStateOf<AboutSheet?>(null) }
    var rangeOpen by rememberSaveable { mutableStateOf(false) }
    var colorsOpen by rememberSaveable { mutableStateOf(false) }
    var license by rememberSaveable { mutableStateOf<LicensedComponent?>(null) }
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.settings_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = onBack,
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()) {
                // Un ajuste que no se pudo leer no desaparece en silencio: se dice arriba, con
                // `Reintentar`, y lo demás sigue a mano.
                if (readFailed) {
                    NoticeCard(
                        text = stringResource(R.string.state_error_read),
                        variant = NoticeVariant.ERROR,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRetryRead,
                        modifier = Modifier.padding(
                            horizontal = spacing.s16,
                            vertical = spacing.s8,
                        ),
                    )
                }
                if (deviceLockMissing) {
                    NoticeCard(
                        text = stringResource(R.string.lock_device_risk),
                        variant = NoticeVariant.WARNING,
                        modifier = Modifier.padding(
                            horizontal = spacing.s16,
                            vertical = spacing.s8,
                        ),
                    )
                }
                // La verificación de stock encontró diferencias: se dice arriba, con cuántos
                // productos, y la salida es pedir ayuda. La app no corrige nada sola.
                overview?.backup?.overview
                    ?.takeIf { it.failureCode == BackupFailureCode.LOCAL_INVARIANT }
                    ?.let { invariantBody(it.invariantFailures, stringResource(R.string.app_name)) }
                    ?.let { body ->
                        NoticeCard(
                            text = body,
                            variant = NoticeVariant.WARNING,
                            actionLabel = stringResource(R.string.settings_support),
                            onAction = { aboutSheet = AboutSheet.SUPPORT },
                            modifier = Modifier.padding(
                                horizontal = spacing.s16,
                                vertical = spacing.s8,
                            ),
                        )
                    }
                overview?.backup?.let { BackupGroup(it, overview.destination, links) }
                usage?.let {
                    AlertsGroup(it, notificationsBlocked, onUsageAction) {
                        costStaleDays?.let { days -> CostStaleDaysRow(days, onProfileAction) }
                    }
                }
                if (profile != null) {
                    BusinessProfileGroup(profile, onProfileAction) {
                        onOpen(AppNavKey.Categories)
                    }
                }
                RegionGroup(overview, onOpen)
                if (tipsEnabled != null) {
                    SalesGroup(tipsEnabled, onTipsChange, tipsSaveFailed, onOpen)
                }
                if (security != null) {
                    SecurityGroup(security, stringResource(R.string.app_name), onSecurityAction)
                    // `Cifrado de copias` va al final de `Seguridad`, tras el bloqueo y el modo
                    // discreto.
                    if (!security.readFailed && security.lock != null) {
                        SettingsRow(
                            title = stringResource(R.string.encryption_title),
                            action =
                                SettingsRowAction.Navigation { onOpen(AppNavKey.BackupEncryption) },
                        )
                    }
                }
                usage?.let { AppearanceGroup(it, onUsageAction) { colorsOpen = true } }
                if (overview != null) {
                    SectionHeader(stringResource(R.string.settings_group_data))
                    if (csvExport != null) {
                        CsvExportRow(csvExport, onCsvExportAction)
                        CsvExportRangeRow(
                            csvExport,
                            onOpen = { rangeOpen = true },
                            onObscured = { onCsvExportAction(CsvExportAction.ObscuredTouch) },
                        )
                    }
                    SettingsRow(
                        title = stringResource(R.string.offsite_title),
                        action = SettingsRowAction.Navigation { onOpen(AppNavKey.LocalCopies) },
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_delete_data),
                        action = SettingsRowAction.Navigation {
                            aboutSheet = AboutSheet.DELETE_GUIDE
                        },
                    )
                }
                AboutGroup(
                    version = version,
                    links = links,
                    onSupport = { aboutSheet = AboutSheet.SUPPORT },
                ) { aboutSheet = AboutSheet.LICENSES }
            }
        }
    }
    profileSheet?.let { BusinessProfileSheet(it, onProfileAction) }
    val rangeCalendar = csvExport?.calendar
    if (rangeOpen && rangeCalendar != null) {
        CsvExportRangeSheet(
            calendar = rangeCalendar,
            onExport = { range ->
                rangeOpen = false
                onCsvExportAction(CsvExportAction.ExportRange(range))
            },
            onDismiss = { rangeOpen = false },
        )
    }
    if (colorsOpen) {
        PaletteSheet(
            selected = AppTheme.palette,
            onSelect = onChoosePalette,
            onDismiss = { colorsOpen = false },
            failed = paletteFailed,
            onRetry = onRetryPalette,
        )
    }
    when (aboutSheet) {
        AboutSheet.LICENSES -> {
            val shown = license
            if (shown == null) {
                LicensesSheet(onOpen = { license = it }, onDismiss = { aboutSheet = null })
            } else {
                // Cerrar el texto vuelve a la lista, que sigue abierta.
                LicenseTextSheet(shown, readLicense, onDismiss = { license = null })
            }
        }

        AboutSheet.DELETE_GUIDE -> DeleteGuideSheet(onDismiss = { aboutSheet = null })

        AboutSheet.SUPPORT ->
            SupportInfoSheet(support, onCopy = onCopySupport, onDismiss = { aboutSheet = null })

        null -> Unit
    }
}

/**
 * `Respaldo`: el estado en el mismo chip de Inicio y, si pide atención, su aviso con la salida. Debajo,
 * dónde se guardan las copias: el usuario del servicio o el nombre de la nube del teléfono. Sin
 * destino, elegir dónde guardar.
 */
@Composable
private fun BackupGroup(
    backup: BackupWording,
    destination: BackupDestinationInfo?,
    links: SettingsLinks,
) {
    val spacing = AppTheme.spacing
    val status = backup.overview.report.status
    SectionHeader(stringResource(R.string.backup_title))
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        BackupStatusChip(backupChipLook(backup), onClick = { links.onOpen(AppNavKey.Backup) })
        // Las diferencias de stock ya se dicen arriba, con su salida a soporte: no se repiten aquí.
        val invariantAbove =
            backup.overview.failureCode == BackupFailureCode.LOCAL_INVARIANT &&
                backup.overview.invariantFailures > 0
        if (status.needsBanner() && !invariantAbove) {
            val (label, target) = bannerExit(status)
            BackupStatusBanner(
                backupBannerLook(backup),
                actionLabel = stringResource(label),
                onAction = {
                    when (target) {
                        BannerExit.CHOOSE -> links.onOpen(AppNavKey.BackupDestinationChoice())
                        BannerExit.POLICY -> links.onReviewPolicy()
                        BannerExit.BACKUP -> links.onOpen(AppNavKey.Backup)
                    }
                },
            )
        }
    }
    if (destination == null) {
        SettingsRow(
            title = stringResource(R.string.backup_action_choose_destination),
            action =
                SettingsRowAction.Navigation {
                    links.onOpen(AppNavKey.BackupDestinationChoice())
                },
        )
    } else {
        SettingsRow(
            title = stringResource(R.string.backup_destination_label),
            summary = destinationText(destination),
            action = SettingsRowAction.Navigation { links.onOpen(AppNavKey.Backup) },
        )
    }
}

/** Adónde lleva el aviso del respaldo. */
private enum class BannerExit { CHOOSE, POLICY, BACKUP }

/** La salida del aviso de [status], la misma que en Inicio. */
private fun bannerExit(status: BackupStatus): Pair<Int, BannerExit> = when (status) {
    BackupStatus.NOT_CONNECTED -> R.string.backup_action_choose_destination to BannerExit.CHOOSE

    BackupStatus.POLICY_PENDING -> R.string.backup_action_review_policy to BannerExit.POLICY

    // Los demás se revisan en la pantalla del respaldo: se nombran para que uno nuevo se decida.
    BackupStatus.CHECK_DUE,
    BackupStatus.BLOCKED_BY_DATA_SAVER,
    BackupStatus.STALE,
    BackupStatus.FAILED,
    BackupStatus.PERMISSION_LOST,
    BackupStatus.CONFLICT,
    BackupStatus.RESTORING,
    BackupStatus.NOTHING_TO_BACK_UP,
    BackupStatus.UPLOADING,
    BackupStatus.HANDED_OFF,
    BackupStatus.UP_TO_DATE,
    BackupStatus.WAITING_FOR_WIFI,
    BackupStatus.WAITING_FOR_NETWORK,
    BackupStatus.PENDING,
    -> R.string.backup_action_review to BannerExit.BACKUP
}

/**
 * El destino en una línea: con WebDAV, el usuario, o el servidor si no hay usuario guardado; con la
 * nube del teléfono, su nombre. Nunca la contraseña ni la dirección completa.
 */
@Composable
private fun destinationText(destination: BackupDestinationInfo): String = when (destination) {
    is BackupDestinationInfo.WebDav -> destination.account ?: destination.host ?: NO_VALUE_TEXT

    is BackupDestinationInfo.Slots ->
        stringResource(
            R.string.backup_destination_connected,
            destination.providerLabel ?: NO_VALUE_TEXT,
        )
}

/**
 * `Región e idioma`, con la moneda del negocio y el idioma elegido. Una moneda sin confirmar lleva
 * su etiqueta de aviso. Sin base, las filas siguen, sin el valor de la moneda.
 */
@Composable
private fun RegionGroup(overview: SettingsOverviewUiState?, onOpen: (AppNavKey) -> Unit) {
    SectionHeader(stringResource(R.string.settings_group_region))
    val currency = overview?.currency
    SettingsRow(
        title = stringResource(R.string.region_title),
        summary = currency?.takeIf { it.isConfirmed }?.code,
        warning =
            if (currency != null && !currency.isConfirmed) {
                stringResource(R.string.region_currency_unconfirmed)
            } else {
                null
            },
        action = SettingsRowAction.Navigation { onOpen(AppNavKey.RegionalSettings()) },
    )
    SettingsRow(
        title = stringResource(R.string.language_title),
        summary =
            when (val language = overview?.language ?: UiLanguage.System) {
                UiLanguage.System -> stringResource(R.string.language_system)
                is UiLanguage.Chosen -> ownName(language.tag)
            },
        action = SettingsRowAction.Navigation { onOpen(AppNavKey.LanguageSettings) },
    )
}

/**
 * `Ventas y cobro`: impuestos, métodos de pago, el interruptor de las propinas, la plantilla del
 * comprobante y las impresoras.
 */
@Composable
private fun SalesGroup(
    tipsEnabled: Boolean,
    onTipsChange: (Boolean) -> Unit,
    saveFailed: Boolean,
    onOpen: (AppNavKey) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_group_sales))
    SettingsRow(
        title = stringResource(R.string.tax_title),
        action = SettingsRowAction.Navigation { onOpen(AppNavKey.TaxSettings) },
    )
    SettingsRow(
        title = stringResource(R.string.payment_method_title),
        action = SettingsRowAction.Navigation { onOpen(AppNavKey.PaymentMethods) },
    )
    SettingsRow(
        title = stringResource(R.string.settings_tips),
        action = SettingsRowAction.Toggle(tipsEnabled, onTipsChange),
    )
    SettingsRow(
        title = stringResource(R.string.receipt_template_title),
        action = SettingsRowAction.Navigation { onOpen(AppNavKey.ReceiptTemplate) },
    )
    SettingsRow(
        title = stringResource(R.string.printer_title),
        action = SettingsRowAction.Navigation { onOpen(AppNavKey.Printers) },
    )
    if (saveFailed) {
        NoticeCard(
            text = stringResource(R.string.state_error_save),
            variant = NoticeVariant.ERROR,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
    }
}

/**
 * `Ayuda y acerca de`: el nombre con su versión y compilación, el aviso de que no reemplaza la
 * contabilidad ni emite facturas, las licencias, los términos y la política de privacidad.
 */
@Composable
private fun AboutGroup(
    version: String?,
    links: SettingsLinks,
    onSupport: () -> Unit,
    onLicenses: () -> Unit,
) {
    val app = stringResource(R.string.app_name)
    SectionHeader(stringResource(R.string.settings_group_help_about))
    SettingsRow(
        title = stringResource(R.string.support_field_version, app),
        summary = version,
        action = SettingsRowAction.Value {},
    )
    // El aviso de alcance legal: no reemplaza la contabilidad, un comprobante no es factura, y no
    // emite documentos fiscales ni está certificada por nadie.
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        for (notice in listOf(
            stringResource(R.string.about_not_accounting, app),
            stringResource(R.string.legal_universal_notice, app),
        )) {
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SettingsRow(
        title = stringResource(R.string.settings_support),
        summary = stringResource(R.string.support_help),
        action = SettingsRowAction.Navigation(onSupport),
    )
    SettingsRow(
        title = stringResource(R.string.settings_licenses),
        action = SettingsRowAction.Navigation(onLicenses),
    )
    SettingsRow(
        title = stringResource(R.string.legal_terms_title),
        action = SettingsRowAction.Navigation(links.onOpenTerms),
    )
    SettingsRow(
        title = stringResource(R.string.settings_privacy),
        action = SettingsRowAction.Navigation(links.onOpenPrivacy),
    )
}

/**
 * El nombre del negocio, que encabeza los comprobantes, y el margen objetivo, del que parten las
 * sugerencias de precio. Cada una abre su hoja. Sin nombre, la fila no inventa uno. Al final,
 * `Categorías` abre la pantalla de categorías de productos y de gastos.
 */
@Composable
private fun BusinessProfileGroup(
    profile: BusinessProfile,
    onAction: (ProfileAction) -> Unit,
    onCategories: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_group_business))
    SettingsRow(
        title = stringResource(R.string.settings_business_name),
        summary = profile.businessName,
        action = SettingsRowAction.Navigation { onAction(ProfileAction.Open(ProfileField.NAME)) },
    )
    SettingsRow(
        title = stringResource(R.string.settings_target_margin),
        summary = LocalAppFormats.current.percent.format(profile.defaultTargetMargin),
        action =
            SettingsRowAction.Navigation {
                onAction(ProfileAction.Open(ProfileField.TARGET_MARGIN))
            },
    )
    SettingsRow(
        title = stringResource(R.string.categories_title),
        action = SettingsRowAction.Navigation(onCategories),
    )
}

/**
 * `Días para costo viejo`: tras cuántos días `Revisar` marca un costo como viejo, o `Apagado` con
 * cero. Abre su hoja.
 */
@Composable
private fun CostStaleDaysRow(days: Int, onAction: (ProfileAction) -> Unit) {
    val summary =
        if (days == 0) {
            stringResource(R.string.settings_cost_stale_days_off)
        } else {
            pluralStringResource(
                R.plurals.settings_cost_stale_days_value,
                days,
                LocalAppFormats.current.quantity.formatCount(days.toLong()),
            )
        }
    SettingsRow(
        title = stringResource(R.string.settings_cost_stale_days),
        summary = summary,
        action =
            SettingsRowAction.Navigation {
                onAction(ProfileAction.Open(ProfileField.COST_STALE_DAYS))
            },
    )
}

@ThemePreviews
@Composable
private fun SettingsEntryScreenPreview() {
    PreviewTheme {
        SettingsEntryScreen(
            onOpen = {},
            onBack = {},
            tipsEnabled = true,
            profile = BusinessProfile("Tienda de muestra", BasisPoints(3_000L)),
            deviceLockMissing = true,
            version = "1.0.0 (1)",
        )
    }
}
