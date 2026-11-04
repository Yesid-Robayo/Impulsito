package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine

/** El registro de las pruebas: guarda cada línea en memoria, con su gravedad. */
class RecordingEventLog : EventLog {
    val entries = mutableListOf<Pair<LogLevel, String>>()

    /** Todo lo registrado, en un solo texto, para buscar lo que no debe aparecer. */
    val allText: String get() = entries.joinToString("\n") { (level, text) -> "$level $text" }

    override fun write(level: LogLevel, line: LogLine) {
        entries += level to line.text
    }
}
