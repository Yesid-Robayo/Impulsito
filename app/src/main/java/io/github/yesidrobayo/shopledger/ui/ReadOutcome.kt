package io.github.yesidrobayo.shopledger.ui

import kotlin.coroutines.cancellation.CancellationException

/**
 * Lo que dio una lectura de la base: el valor, que puede ser nulo cuando lo leído ya no existe, o una
 * falla. Una pantalla no se cierra por una falla de lectura: la muestra como un error con
 * `Reintentar`, y nunca como si no hubiera datos.
 */
sealed interface ReadOutcome<out T> {
    data class Read<out T>(val value: T) : ReadOutcome<T>

    data object Failed : ReadOutcome<Nothing>
}

/** Corre [block] y separa lo leído de una falla. La cancelación sigue su camino: no es una falla. */
suspend fun <T> readOutcome(block: suspend () -> T): ReadOutcome<T> = try {
    ReadOutcome.Read(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    ReadOutcome.Failed
}

/** Lo que devuelve [block], o nulo si la lectura falló. */
suspend fun <T : Any> readOrNull(block: suspend () -> T): T? =
    when (val outcome = readOutcome(block)) {
        is ReadOutcome.Read -> outcome.value
        ReadOutcome.Failed -> null
    }
