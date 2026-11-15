package io.github.yesidrobayo.shopledger.di

import io.github.yesidrobayo.shopledger.domain.port.LogEvent

/** Lo que registra la app fuera de la capa de datos. Cada texto es fijo y no lleva datos. */
internal enum class AppEvent(override val text: String) : LogEvent {
    BACKGROUND_WORK_FAILED("Falló un trabajo en segundo plano"),
    LOW_STOCK_DELIVERY_FAILED("Falló la entrega de los avisos de stock bajo"),
    EXPIRY_DELIVERY_FAILED("Falló la entrega de los avisos de vencimiento"),
    DAILY_SUMMARY_FAILED("Falló el resumen del día"),
    RECURRING_DELIVERY_FAILED("Falló la entrega de los recordatorios de gastos recurrentes"),
    LIMIT_DELIVERY_FAILED("Falló la entrega del aviso del límite de ingresos"),
}
