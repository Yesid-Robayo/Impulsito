package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.NoticeSettings
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.WholeNumberField
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.unit.EditorContainer
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure

/** El dato del perfil del negocio que se edita en su hoja. */
enum class ProfileField {
    /** El nombre que encabeza los comprobantes. */
    NAME,

    /** El margen del que parten las sugerencias de precio. */
    TARGET_MARGIN,

    /** Los días tras los que `Revisar` marca un costo como viejo; cero lo apaga. */
    COST_STALE_DAYS,
}

/** Por qué lo escrito en la hoja del perfil no se puede guardar. */
enum class ProfileProblem {
    NAME_TOO_LONG,
    NOT_A_NUMBER,
    TOO_MANY_DECIMALS,

    /** Negativo o mayor que 99 %. */
    OUT_OF_RANGE,

    /** Días negativos o de más de cien años. */
    DAYS_OUT_OF_RANGE,
}

/**
 * La hoja de un dato del perfil mientras se escribe: el dato, lo escrito tal cual, su problema si lo
 * hay y si el último guardado falló. Lo escrito siempre se conserva.
 */
@Immutable
data class ProfileSheet(
    val field: ProfileField,
    val text: String,
    val problem: ProfileProblem? = null,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
)

/** Lo que la persona hace con el perfil del negocio. */
sealed interface ProfileAction {
    /** Abre la hoja de [field] con lo guardado. */
    data class Open(val field: ProfileField) : ProfileAction

    data class Text(val text: String) : ProfileAction

    data object Save : ProfileAction

    data object Dismiss : ProfileAction
}

/**
 * La hoja de un dato del perfil: el nombre del negocio, opcional, el margen objetivo, de 0 a 99 %,
 * o los días para costo viejo, donde 0 apaga ese aviso. `Guardar cambios` va abajo, con el aviso
 * de una falla encima.
 */
@Composable
fun BusinessProfileSheet(sheet: ProfileSheet, onAction: (ProfileAction) -> Unit) {
    val title = when (sheet.field) {
        ProfileField.NAME -> stringResource(R.string.settings_business_name)
        ProfileField.TARGET_MARGIN -> stringResource(R.string.settings_target_margin)
        ProfileField.COST_STALE_DAYS -> stringResource(R.string.settings_cost_stale_days)
    }
    EditorContainer(
        asPanel = false,
        title = title,
        onDismiss = { onAction(ProfileAction.Dismiss) },
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                sheet.saveFailed?.let {
                    NoticeCard(
                        text = stringResource(saveErrorText(it.outOfSpace)),
                        variant = NoticeVariant.ERROR,
                        icon = R.drawable.ic_error,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.region_save_action),
                    onClick = { onAction(ProfileAction.Save) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
                )
            }
        },
    ) {
        val error = sheet.problem?.let { problemText(it) }
        when (sheet.field) {
            ProfileField.NAME ->
                AppTextField(
                    value = sheet.text,
                    onValueChange = { onAction(ProfileAction.Text(it)) },
                    label = title,
                    error = error,
                    imeAction = ImeAction.Done,
                )

            ProfileField.TARGET_MARGIN ->
                PercentField(
                    value = sheet.text,
                    onValueChange = { onAction(ProfileAction.Text(it)) },
                    label = title,
                    error = error,
                    imeAction = ImeAction.Done,
                )

            ProfileField.COST_STALE_DAYS ->
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                    WholeNumberField(
                        value = sheet.text,
                        onValueChange = { onAction(ProfileAction.Text(it)) },
                        label = title,
                        error = error,
                        imeAction = ImeAction.Done,
                    )
                    Text(
                        text = stringResource(R.string.settings_cost_stale_days_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
private fun problemText(problem: ProfileProblem): String = when (problem) {
    ProfileProblem.NAME_TOO_LONG -> stringResource(R.string.error_name_long)

    ProfileProblem.NOT_A_NUMBER -> stringResource(R.string.field_error_number)

    ProfileProblem.TOO_MANY_DECIMALS -> stringResource(R.string.error_percent_decimals)

    ProfileProblem.OUT_OF_RANGE -> {
        val percent = LocalAppFormats.current.percent
        stringResource(
            R.string.error_percent_range,
            percent.format(BasisPoints.ZERO),
            percent.format(BasisPoints.MAX_TARGET_MARGIN),
        )
    }

    ProfileProblem.DAYS_OUT_OF_RANGE -> {
        val quantity = LocalAppFormats.current.quantity
        stringResource(
            R.string.settings_cost_stale_days_range,
            quantity.formatCount(0L),
            quantity.formatCount(NoticeSettings.MAX_COST_STALE_DAYS.toLong()),
        )
    }
}
