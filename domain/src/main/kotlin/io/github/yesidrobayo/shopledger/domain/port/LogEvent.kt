package io.github.yesidrobayo.shopledger.domain.port

/**
 * Lo que pasó, con un texto fijo en español: "Base abierta", "Falló un trabajo en segundo plano".
 *
 * [EventLog] solo acepta eventos que son valores de un `enum`: así cada texto se escribe una sola
 * vez, al declararlo, y nunca se arma con lo que la persona escribió. Lo variable de cada línea va
 * en sus [LogField], que solo admiten números, estados y códigos.
 *
 * El texto nunca lleva datos del negocio: ni montos, ni cantidades, ni nombres, ni notas.
 */
interface LogEvent {
    /** El texto fijo del evento, en español. */
    val text: String
}
