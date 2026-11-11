package io.github.yesidrobayo.shopledger.ui.home

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary

/**
 * Lo que muestra Inicio. Es un resumen: se lee de la base local, así que lo normal es no ver nunca
 * el estado de carga.
 */
sealed interface HomeUiState {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : HomeUiState

    /**
     * El día y lo que requiere atención.
     *
     * @param today hoy en la zona del negocio, o `null` si este teléfono no conoce esa zona.
     * @param zone la zona del negocio, para la hora de cada venta, o `null` igual que [today].
     * @param hero las ventas de hoy.
     * @param firstUse si el negocio todavía no tiene productos: la primera acción es crear uno.
     * @param recentSales las últimas ventas, de la más nueva a la más antigua, también las
     *   anuladas, que se ven apagadas como en el historial. Vacía si nunca se vendió.
     */
    data class Content(
        val today: BusinessDate?,
        val zone: BusinessTimeZone?,
        val hero: TodayHero,
        val firstUse: Boolean,
        val recentSales: List<SaleHistoryRow>,
    ) : HomeUiState

    /** No se pudo leer. No se pierde nada: se puede reintentar. */
    data object ReadError : HomeUiState
}

/** Lo que dice el héroe de las ventas de hoy. */
sealed interface TodayHero {
    /**
     * Las cifras de hoy no se pueden mostrar: la zona del negocio no existe en este teléfono, o una
     * suma no cabe. El héroe muestra «sin valor», nunca un cero que parecería medido.
     */
    data object Unavailable : TodayHero

    /** Todavía no hay ventas hoy: el héroe dice cero y espera la primera, sin línea de ganancia. */
    data object NoSalesYet : TodayHero

    /** Lo vendido hoy, su número de ventas, la ganancia en ventas y el margen. */
    data class Sales(val summary: SalesSummary) : TodayHero
}

/** Lo que la persona puede hacer en Inicio. */
sealed interface HomeAction {
    data object OpenSettings : HomeAction

    data object OpenBusiness : HomeAction

    /** La acción del primer uso: crear el primer producto. */
    data object CreateFirstProduct : HomeAction

    data object OpenAllSales : HomeAction

    data class OpenSale(val saleId: Long) : HomeAction

    data object Retry : HomeAction

    /** El chip, el banner o un aviso del respaldo: abre su pantalla. */
    data object OpenBackup : HomeAction

    /** Sin destino: lleva a elegir dónde guardar. */
    data object ChooseDestination : HomeAction

    /** Copias en pausa por la política: la muestra para aceptarla. */
    data object ReviewPolicy : HomeAction
}
