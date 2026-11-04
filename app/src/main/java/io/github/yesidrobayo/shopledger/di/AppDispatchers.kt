package io.github.yesidrobayo.shopledger.di

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Los hilos de la app, inyectados para que las pruebas puedan reemplazarlos.
 *
 * @param main la interfaz: nunca toca el disco ni la red.
 * @param io el disco y la red.
 * @param default los cálculos largos.
 */
class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
)
