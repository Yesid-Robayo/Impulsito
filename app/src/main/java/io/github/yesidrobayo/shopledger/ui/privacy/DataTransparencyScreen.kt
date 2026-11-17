package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.LocalDemoMode
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * `Qué datos hay y adónde van`: en lenguaje simple, qué guarda la app, adónde va cada cosa y cuál
 * es el estado real ahora, sin promesas.
 *
 * 1. Un aviso, si la persona aceptó una política anterior: `Aceptar la política nueva` o `Decidir
 *    después`. No aceptar nunca bloquea los datos, las ventas ni exportar.
 * 2. `Ahora mismo`: copias, cifrado, segundo destino, bloqueo y búsqueda de versión, leídos del
 *    teléfono. Un dato que no se pudo leer lo dice, y nunca muestra uno anterior.
 * 3. `Qué datos hay`, `Adónde van`, con exactamente los destinos de red de la app, y `Lo que
 *    no hay`.
 * 4. `Política y términos`: la política la abre el navegador, y los términos se leen desde los
 *    recursos, sin internet.
 *
 * No muestra cifras del negocio. Columna centrada de 600 dp en una ventana ancha.
 */
@Composable
fun DataTransparencyScreen(
    uiState: DataTransparencyUiState,
    onAction: (DataTransparencyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.transparency_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(DataTransparencyAction.Back) },
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
                Column(
                    Modifier
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth()
                        .padding(bottom = spacing.s24),
                ) {
                    if (uiState.showPolicyNotice) PolicyNotice(uiState.accepting, onAction)
                    Text(
                        stringResource(R.string.transparency_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    )
                    SectionHeader(stringResource(R.string.transparency_now_title))
                    if (uiState.loading) {
                        NowSkeleton()
                    } else {
                        NowSection(uiState)
                    }
                    DataSection()
                    DestinationsSection(uiState)
                    NothingElseSection()
                    PolicySection(onAction)
                }
            }
        }
    }
}

/** El aviso de una política nueva por aceptar, con sus dos salidas. */
@Composable
private fun PolicyNotice(accepting: Boolean, onAction: (DataTransparencyAction) -> Unit) {
    NoticeCard(
        text =
            stringResource(R.string.transparency_policy_changed_title) + "\n" +
                stringResource(R.string.transparency_policy_changed_body),
        variant = NoticeVariant.WARNING,
        actionLabel = stringResource(R.string.transparency_accept_action),
        onAction = { if (!accepting) onAction(DataTransparencyAction.AcceptPolicy) },
        secondaryLabel = stringResource(R.string.transparency_decide_later_action),
        onSecondary = { onAction(DataTransparencyAction.DecideLater) },
        // TalkBack lo dice al aparecer, sin mover el foco: las dos salidas siguen siendo botones.
        modifier =
            Modifier
                .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Mientras se lee: unas líneas grises tras 200 ms, para que una lectura rápida no parpadee. */
@Composable
private fun NowSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(horizontal = AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            repeat(NOW_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.iconInline))
            }
        }
    }
}

/** `Ahora mismo`: cada fila sale de un dato leído, o dice que no está disponible. */
@Composable
private fun NowSection(uiState: DataTransparencyUiState) {
    val unavailable = stringResource(R.string.transparency_unavailable)
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        // Con datos de ejemplo nada se respalda: se dice antes que el estado de las copias.
        if (LocalDemoMode.current) FactLine(stringResource(R.string.transparency_now_demo))
        BackupsFact(uiState.destination, unavailable)
        if (uiState.uploadsPaused) {
            StatusTag(
                stringResource(R.string.transparency_now_backups_paused),
                icon = R.drawable.ic_warning,
                tone = StatusTone.WARNING,
            )
        }
        when (val encryption = uiState.encryptionOn) {
            is Fact.Known -> FactPair {
                FactLine(
                    stringResource(
                        if (encryption.value) {
                            R.string.transparency_now_encryption_on
                        } else {
                            R.string.transparency_now_encryption_off
                        },
                    ),
                )
                FactDetail(
                    stringResource(
                        if (encryption.value) {
                            R.string.transparency_backup_encrypted
                        } else {
                            R.string.transparency_backup_plain
                        },
                    ),
                )
            }

            Fact.Unavailable -> FactLine(unavailable)
        }
        FactLine(
            when (val second = uiState.second) {
                is Fact.Known -> when (val value = second.value) {
                    SecondFact.None -> stringResource(R.string.transparency_now_second_none)

                    is SecondFact.Folder ->
                        stringResource(
                            R.string.transparency_now_second_folder,
                            value.folderName ?: NO_VALUE_TEXT,
                        )
                }

                Fact.Unavailable -> unavailable
            },
        )
        FactLine(
            uiState.appLock.text(
                R.string.transparency_now_lock_on,
                R.string.transparency_now_lock_off,
                unavailable,
            ),
        )
        FactLine(
            uiState.updateCheck.text(
                R.string.transparency_now_update_on,
                R.string.transparency_now_update_off,
                unavailable,
            ),
        )
    }
}

/**
 * Las copias: adónde van, con la cuenta debajo si es un servicio WebDAV, o que no hay destino.
 * Nunca la contraseña ni la dirección completa.
 */
@Composable
private fun BackupsFact(destination: Fact<BackupDestinationInfo?>, unavailable: String) {
    when (destination) {
        is Fact.Known -> when (val value = destination.value) {
            is BackupDestinationInfo.WebDav -> FactPair {
                FactLine(
                    stringResource(
                        R.string.transparency_now_backups_webdav,
                        value.host ?: value.account ?: NO_VALUE_TEXT,
                    ),
                )
                value.account?.let { FactDetail(it) }
            }

            is BackupDestinationInfo.Slots ->
                FactLine(
                    stringResource(
                        R.string.transparency_now_backups_slots,
                        value.providerLabel ?: NO_VALUE_TEXT,
                    ),
                )

            null -> FactLine(stringResource(R.string.transparency_now_backups_off))
        }

        Fact.Unavailable -> FactLine(unavailable)
    }
}

/** El texto de un interruptor leído, o que no está disponible. */
@Composable
private fun Fact<Boolean>.text(on: Int, off: Int, unavailable: String): String = when (this) {
    is Fact.Known -> stringResource(if (value) on else off)
    Fact.Unavailable -> unavailable
}

/**
 * Una fila de `Ahora mismo` con su detalle debajo: TalkBack la lee como un solo foco, etiqueta y
 * valor juntos. Conserva la separación de las filas sueltas.
 */
@Composable
private fun FactPair(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        content = content,
    )
}

@Composable
private fun FactLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun FactDetail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `Qué datos hay`: el negocio, los datos de otras personas, las fotos y lo técnico. */
@Composable
private fun DataSection() {
    SectionHeader(stringResource(R.string.transparency_data_title))
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        for ((title, body) in listOf(
            R.string.transparency_data_business to R.string.transparency_data_business_body,
            R.string.transparency_data_customers to R.string.transparency_data_customers_body,
            R.string.transparency_data_photos to R.string.transparency_data_photos_body,
            R.string.transparency_data_technical to R.string.transparency_data_technical_body,
        )) {
            TitledText(stringResource(title), stringResource(body))
        }
    }
}

@Composable
private fun TitledText(title: String, body: String) {
    Column(
        Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * `Adónde van`: exactamente los destinos de red de la app. El servicio elegido, si lo hay, o la
 * nube del teléfono, que la app no usa como red; el archivo público de versión, y el APK de la
 * versión anunciada, que baja `Actualizar`. Los dos últimos se ven siempre, también con la
 * búsqueda apagada.
 */
@Composable
private fun DestinationsSection(uiState: DataTransparencyUiState) {
    val app = stringResource(R.string.app_name)
    SectionHeader(stringResource(R.string.transparency_where_title))
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        when (val destination = uiState.destination) {
            is Fact.Known -> when (val value = destination.value) {
                is BackupDestinationInfo.WebDav ->
                    DestinationCard(
                        stringResource(
                            R.string.transparency_dest_webdav_title,
                            value.host ?: value.account ?: NO_VALUE_TEXT,
                        ),
                    ) {
                        DestinationLine(R.string.transparency_dest_webdav_purpose)
                        DestinationLine(R.string.transparency_dest_webdav_sends)
                        DestinationLine(R.string.transparency_dest_webdav_off)
                    }

                is BackupDestinationInfo.Slots ->
                    DestinationCard(
                        stringResource(
                            R.string.transparency_dest_slots_title,
                            value.providerLabel ?: NO_VALUE_TEXT,
                        ),
                    ) {
                        DestinationLine(R.string.transparency_dest_slots_purpose)
                        DestinationLine(R.string.transparency_dest_slots_sends)
                        DestinationLine(R.string.transparency_dest_webdav_off)
                    }

                // Sin destino conectado, la app no sube nada: no hay tarjeta del servicio.
                null -> Unit
            }

            Fact.Unavailable ->
                DestinationCard(stringResource(R.string.transparency_unavailable)) {}
        }
        val host = stringResource(R.string.transparency_manifest_host)
        DestinationCard(stringResource(R.string.transparency_dest_manifest_title, host)) {
            DestinationLine(R.string.transparency_dest_manifest_purpose)
            DestinationLine(R.string.transparency_dest_manifest_sends)
            Text(
                stringResource(R.string.transparency_dest_manifest_ip, host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DestinationLine(R.string.transparency_dest_manifest_off)
        }
        // El APK de la versión anunciada, solo al tocar `Actualizar`. Se apaga con la búsqueda:
        // sin ella no hay versión anunciada ni botón.
        val releases = stringResource(R.string.transparency_apk_host)
        DestinationCard(stringResource(R.string.transparency_dest_apk_title, releases)) {
            DestinationLine(R.string.transparency_dest_apk_purpose)
            DestinationLine(R.string.transparency_dest_manifest_sends)
            Text(
                stringResource(R.string.transparency_dest_apk_ip, releases),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.transparency_dest_apk_check, app),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DestinationLine(R.string.transparency_dest_manifest_off)
        }
        Text(
            stringResource(R.string.transparency_no_other, app),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DestinationCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        // Un foco por tarjeta, en orden: destino, para qué, qué envía y cómo se apaga.
        Column(
            Modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun DestinationLine(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

/** `Lo que no hay` y `Otras apps`: sin cuentas, anuncios, analítica ni servidores propios. */
@Composable
private fun NothingElseSection() {
    val app = stringResource(R.string.app_name)
    SectionHeader(stringResource(R.string.transparency_none_title))
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Text(
            stringResource(R.string.transparency_none_body, app),
            style = MaterialTheme.typography.bodyMedium,
        )
        TitledText(
            stringResource(R.string.transparency_other_apps_title),
            stringResource(R.string.transparency_other_apps_body, app),
        )
    }
}

/** `Política y términos`: la política en el navegador, y los términos dentro de la app. */
@Composable
private fun PolicySection(onAction: (DataTransparencyAction) -> Unit) {
    SectionHeader(stringResource(R.string.transparency_policy_section_title))
    SettingsRow(
        title = stringResource(R.string.settings_privacy),
        action = SettingsRowAction.Navigation { onAction(DataTransparencyAction.OpenPrivacy) },
    )
    SettingsRow(
        title = stringResource(R.string.legal_terms_title),
        action = SettingsRowAction.Navigation { onAction(DataTransparencyAction.OpenTerms) },
    )
}

/** Las filas de `Ahora mismo`: copias, cifrado, segundo destino, bloqueo y búsqueda de versión. */
private const val NOW_ROWS = 5

@ThemePreviews
@Composable
private fun DataTransparencyScreenPreview() {
    PreviewTheme {
        DataTransparencyScreen(
            DataTransparencyUiState(
                loading = false,
                consent = Fact.Known(PolicyConsentStatus.OUTDATED),
                destination = Fact.Known(BackupDestinationInfo.WebDav("negocio", "example.com")),
                encryptionOn = Fact.Known(false),
                second = Fact.Known(SecondFact.None),
                appLock = Fact.Known(true),
                updateCheck = Fact.Unavailable,
            ),
            onAction = {},
        )
    }
}
