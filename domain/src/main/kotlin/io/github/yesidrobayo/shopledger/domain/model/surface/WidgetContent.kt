package io.github.yesidrobayo.shopledger.domain.model.surface

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que el widget sabe del negocio al dibujarse, leído de la base sin migrarla.
 *
 * @property hasProducts si hay algún producto sin archivar.
 * @property salesCount las ventas activas de hoy, o `null` si no se sabe qué día es o una suma no
 *   cabe: entonces el widget no muestra cifras del día.
 * @property netSales lo vendido hoy sin impuesto, o `null` si no se sabe. Solo se dibuja si la
 *   persona lo pidió y el teléfono está abierto.
 * @property lowStockCount cuántos productos se están acabando, con el mismo conteo que Inicio.
 * @property backupFailed si el respaldo dejó un fallo que la persona tiene que ver.
 */
data class WidgetFacts(
    val hasProducts: Boolean,
    val salesCount: Long?,
    val netSales: Money?,
    val lowStockCount: Int,
    val backupFailed: Boolean,
)

/** Lo que dibuja el widget de venta rápida. */
sealed interface WidgetContent {
    /**
     * La base no está lista, porque espera una migración o una restauración, no abre, o no se sabe
     * qué día es: solo `Vender`, que abre la app. Nunca un error a la vista.
     */
    data object Neutral : WidgetContent

    /** Todavía no hay productos: invita a crear el primero. */
    data object NoProducts : WidgetContent

    /**
     * El día en conteos.
     *
     * @property amountsChosen si la persona pidió montos en este widget: decide el icono del ojo.
     * @property amount lo vendido hoy, solo si se pidió, el teléfono está abierto y el modo
     *   discreto está apagado.
     */
    data class Summary(
        val salesCount: Long,
        val lowStockCount: Int,
        val amountsChosen: Boolean,
        val amount: Money?,
        val backupFailed: Boolean,
    ) : WidgetContent
}
