package io.github.yesidrobayo.shopledger.lock

import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Si Android marcó el toque porque otra ventana tapa el punto tocado. Una ventana que tapa otra parte
 * de la pantalla, como una burbuja en una esquina, solo deja la marca parcial: ese toque no se pierde.
 */
fun MotionEvent.isObscured(): Boolean = flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0

/**
 * Rechaza en esta acción sensible los toques que llegan a través de otra app dibujada encima, y avisa
 * con [onRejected].
 *
 * Va antes de `clickable` en la cadena: en la pasada inicial consume el gesto entero, y el clic, que
 * corre después, lo ve consumido y no se dispara. Solo esta acción pierde el toque: el resto de la
 * pantalla sigue respondiendo con un filtro de pantalla o una burbuja encima. TalkBack activa la
 * acción por accesibilidad, sin toque, así que nunca la pierde.
 */
fun Modifier.rejectObscuredTouches(onRejected: () -> Unit): Modifier = pointerInput(onRejected) {
    awaitPointerEventScope {
        var rejecting = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.changedToDown() }) {
                rejecting = event.motionEvent?.isObscured() == true
                if (rejecting) onRejected()
            }
            if (rejecting) event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Decide, para una pantalla entera sensible, qué toques se descartan: un gesto que empieza tapado se
 * descarta completo, hasta levantar el dedo, para que nunca quede medio gesto en la pantalla.
 */
class ObscuredGestureFilter {
    private var rejecting = false

    /**
     * `true` si el evento de [actionMasked] se descarta. [obscured] dice si llegó tapado; solo cuenta
     * al bajar el dedo. [onRejected] avisa una vez por gesto descartado.
     */
    fun reject(actionMasked: Int, obscured: Boolean, onRejected: () -> Unit): Boolean {
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            rejecting = obscured
            if (rejecting) onRejected()
        }
        val rejected = rejecting
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            rejecting = false
        }
        return rejected
    }
}
