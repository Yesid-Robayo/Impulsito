package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import kotlin.math.roundToInt

/**
 * Cuánto tienen que subir los mensajes de la navegación para no tapar el área fija de abajo de la
 * pantalla visible, como el panel de la venta con `Registrar venta`.
 *
 * Un mensaje como `Venta registrada` con `Deshacer` vive en la navegación y sobrevive a la pantalla
 * que lo pidió: sigue a la vista cuando la persona abre la venta siguiente. Sin esto quedaba encima
 * del botón principal, y un toque podía caer en `Deshacer` y anular la venta anterior.
 *
 * Cada área fija se marca con [keepsMessagesAbove]; la navegación lee [bottomPx] y deja el mensaje
 * encima de la más alta. Durante una transición pueden estar dos pantallas a la vez: manda la mayor.
 */
@Stable
class MessageClearance {
    private val areas = mutableStateMapOf<Any, Int>()

    private val highest by derivedStateOf { areas.values.maxOrNull() ?: 0 }

    /**
     * Píxeles desde el borde de abajo de la ventana hasta el borde de arriba del área fija más alta,
     * o 0 si la pantalla visible no tiene ninguna.
     */
    val bottomPx: Int get() = highest

    internal fun report(owner: Any, px: Int) {
        if (px > 0) areas[owner] = px else areas.remove(owner)
    }

    internal fun clear(owner: Any) {
        areas.remove(owner)
    }
}

/**
 * El espacio de los mensajes que da la navegación. Fuera de ella, en vistas previas y pruebas de una
 * pantalla suelta, cada lectura recibe uno propio que nadie mira: marcar un área no hace nada.
 */
val LocalMessageClearance = staticCompositionLocalOf { MessageClearance() }

/**
 * Marca el área fija de abajo de una pantalla, la que lleva su botón principal: mientras esté a la
 * vista, los mensajes de la navegación se muestran encima de ella y nunca la tapan.
 */
fun Modifier.keepsMessagesAbove(): Modifier = this then KeepsMessagesAboveElement

private data object KeepsMessagesAboveElement : ModifierNodeElement<KeepsMessagesAboveNode>() {
    override fun create(): KeepsMessagesAboveNode = KeepsMessagesAboveNode()

    override fun update(node: KeepsMessagesAboveNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "keepsMessagesAbove"
    }
}

private class KeepsMessagesAboveNode :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode {
    private var clearance: MessageClearance? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val target = currentValueOf(LocalMessageClearance)
        if (target !== clearance) {
            clearance?.clear(this)
            clearance = target
        }
        // Desde el borde de arriba del área hasta el de abajo de la ventana, con la barra del
        // sistema y el teclado incluidos: el mensaje se apoya justo encima.
        val windowHeight = coordinates.findRootCoordinates().size.height
        target.report(this, (windowHeight - coordinates.boundsInRoot().top).roundToInt())
    }

    override fun onDetach() {
        clearance?.clear(this)
        clearance = null
    }
}
