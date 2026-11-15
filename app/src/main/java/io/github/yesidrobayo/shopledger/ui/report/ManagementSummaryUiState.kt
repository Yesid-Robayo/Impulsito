package io.github.yesidrobayo.shopledger.ui.report

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import kotlinx.datetime.DayOfWeek

/**
 * El estado del resumen de gestión: el período elegido, sus cifras, lo que se incluye y cómo va la
 * generación del paquete.
 *
 * [period], [today] y [weekStartDay] faltan hasta conocer el calendario del negocio. [chosen] es
 * lo que va encendido entre lo que [ManagementSummary.contents] ofrece.
 */
@Immutable
data class ManagementSummaryUiState(
    val period: ReportPeriod? = null,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val canGoNext: Boolean = false,
    val content: SummaryScreenContent = SummaryScreenContent.Loading,
    val chosen: Set<SummaryContent> = emptySet(),
    val choosingRange: Boolean = false,
    val generation: SummaryGeneration = SummaryGeneration.Idle,
) {
    /** Las cifras, si ya llegaron. */
    val summary: ManagementSummary? get() = (content as? SummaryScreenContent.Ready)?.summary

    /** Si se puede generar: con cifras, con movimientos y sin otra generación en curso. */
    val canGenerate: Boolean
        get() = summary?.hasMovements == true && generation != SummaryGeneration.Working
}

/** Lo que muestra la tarjeta de cifras. */
sealed interface SummaryScreenContent {
    /** Leyendo por primera vez. Al cambiar de período, se conserva lo anterior. */
    data object Loading : SummaryScreenContent

    /** No se pudo leer, o una cifra no cabe. Se puede reintentar. */
    data object ReadError : SummaryScreenContent

    /** El teléfono no conoce la zona del negocio, así que no sabe qué mes es hoy. */
    data object UnknownZone : SummaryScreenContent

    /** Las cifras del período. */
    data class Ready(val summary: ManagementSummary) : SummaryScreenContent
}

/** Cómo va el paquete. */
sealed interface SummaryGeneration {
    /** Nada en curso. */
    data object Idle : SummaryGeneration

    /** Escribiendo el PDF y los CSV: el botón muestra su indicador e ignora más toques. */
    data object Working : SummaryGeneration

    /** El paquete está listo en la carpeta de lo que sale de la app: abre la hoja. */
    data class Ready(val fileName: String, val sizeBytes: Long) : SummaryGeneration

    /** No hubo espacio en el teléfono. No quedó nada escrito. */
    data object NoSpace : SummaryGeneration

    /** Otra falla. No quedó nada escrito, y se puede reintentar. */
    data object Failed : SummaryGeneration
}

/**
 * El pedido de escribir el paquete, que resuelve la navegación con el PDF y el caso de uso.
 * [attempt] distingue dos pedidos seguidos con lo mismo.
 */
@Immutable
data class SummaryJob(
    val summary: ManagementSummary,
    val chosen: Set<SummaryContent>,
    val today: BusinessDate,
    val attempt: Long,
)

/** Cómo terminó escribir el paquete. */
sealed interface SummaryOutcome {
    data class Saved(val fileName: String, val sizeBytes: Long) : SummaryOutcome

    data object NoSpace : SummaryOutcome

    data object Failed : SummaryOutcome
}

/** Lo que la persona hace en el resumen de gestión. */
sealed interface SummaryAction {
    data object Back : SummaryAction

    data object PreviousPeriod : SummaryAction

    data object NextPeriod : SummaryAction

    /** Abre la elección de un rango de fechas. */
    data object ChooseRange : SummaryAction

    /** Cierra la elección del rango sin cambiar nada. */
    data object DismissRange : SummaryAction

    /** Muestra el rango entre [from] y [to], los dos incluidos, en cualquier orden. */
    data class ShowRange(val from: BusinessDate, val to: BusinessDate) : SummaryAction

    /** Enciende o apaga un grupo de archivos. */
    data class Include(val content: SummaryContent, val on: Boolean) : SummaryAction

    data object Retry : SummaryAction

    /** Abre la configuración regional para elegir la zona del negocio. */
    data object ChooseZone : SummaryAction

    /** Pide generar. La navegación confirma con el bloqueo, si está encendido, antes de seguir. */
    data object Generate : SummaryAction

    /** La verificación pasó: empieza a escribir el paquete. */
    data object Confirmed : SummaryAction

    /** La navegación terminó de escribir el paquete pedido. */
    data class Finished(val outcome: SummaryOutcome) : SummaryAction

    /** Comparte el paquete listo por el selector del sistema: lo resuelve la navegación. */
    data object Share : SummaryAction

    /** Guarda el paquete listo donde elija la persona: lo resuelve la navegación. */
    data object SaveToFolder : SummaryAction

    /** Cierra la hoja `Resumen listo` o el aviso de error. La carpeta se vacía. */
    data object DismissResult : SummaryAction
}
