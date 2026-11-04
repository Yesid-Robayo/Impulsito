package io.github.yesidrobayo.shopledger.data.log

import io.github.yesidrobayo.shopledger.domain.port.LogEvent

/** Lo que registra la capa de datos. Cada texto es fijo y no lleva datos del negocio. */
internal enum class DataEvent(override val text: String) : LogEvent {
    DATABASE_OPENED("Base abierta"),
    DATABASE_OPEN_FAILED("La base no abrió"),
}
