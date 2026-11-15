package io.github.yesidrobayo.shopledger.navigation

import android.content.Intent

/**
 * Un destino que pide quien abre la actividad principal desde fuera de la navegación, como un aviso
 * de stock bajo.
 *
 * La actividad principal está exportada: cualquier app puede lanzarla con lo que quiera dentro. Por
 * eso los destinos son una lista cerrada, cada uno con su acción fija. Una acción desconocida, un
 * producto sin número válido o un extra que no se puede leer no abren nada: la app abre como
 * siempre. El pedido solo lleva números, nunca un nombre ni una ruta.
 */
sealed interface AppOpenRequest {
    /** La pantalla que abre. */
    val key: AppNavKey

    /**
     * El número del aviso pendiente que lo lleva. Dos pedidos del mismo tipo con distinto número
     * son avisos distintos: con el mismo, el segundo cambiaría el destino del primero.
     */
    val requestCode: Int

    /** Escribe el pedido en [intent], que ya apunta a la actividad principal. */
    fun writeTo(intent: Intent)

    /** El detalle de un producto, desde su aviso de stock bajo. */
    data class Product(val productId: Long) : AppOpenRequest {
        override val key: AppNavKey get() = AppNavKey.ProductDetail(productId)

        // Dos productos solo comparten número si sus id difieren en un múltiplo de 2^32.
        override val requestCode: Int get() = productId.toInt()

        override fun writeTo(intent: Intent) {
            intent.action = ACTION_OPEN_PRODUCT
            intent.putExtra(EXTRA_PRODUCT_ID, productId)
        }
    }

    /** La lista de lo que se está acabando, desde el resumen de varios avisos. */
    data object LowStock : AppOpenRequest {
        override val key: AppNavKey get() = AppNavKey.Inventory(InventoryFilter.LOW_STOCK)
        override val requestCode: Int get() = 0

        override fun writeTo(intent: Intent) {
            intent.action = ACTION_OPEN_LOW_STOCK
        }
    }

    /** La lista de lotes por vencer del inventario, desde un aviso de vencimiento. */
    data object Expiring : AppOpenRequest {
        override val key: AppNavKey get() = AppNavKey.Inventory(InventoryFilter.EXPIRING)
        override val requestCode: Int get() = 0

        override fun writeTo(intent: Intent) {
            intent.action = ACTION_OPEN_EXPIRING
        }
    }

    /** Los gastos recurrentes, desde un recordatorio de uno por confirmar. */
    data object Recurring : AppOpenRequest {
        override val key: AppNavKey get() = AppNavKey.RecurringExpenses
        override val requestCode: Int get() = 0

        override fun writeTo(intent: Intent) {
            intent.action = ACTION_OPEN_RECURRING
        }
    }

    /** `Metas y límites`, desde el aviso del límite de ingresos. */
    data object GoalsAndLimits : AppOpenRequest {
        override val key: AppNavKey get() = AppNavKey.GoalsAndLimits
        override val requestCode: Int get() = 0

        override fun writeTo(intent: Intent) {
            intent.action = ACTION_OPEN_LIMITS
        }
    }

    companion object {
        /** Abre el detalle del producto de [EXTRA_PRODUCT_ID]. */
        const val ACTION_OPEN_PRODUCT = "io.github.yesidrobayo.shopledger.action.OPEN_PRODUCT"

        /** Abre la lista de lo que se está acabando. */
        const val ACTION_OPEN_LOW_STOCK = "io.github.yesidrobayo.shopledger.action.OPEN_LOW_STOCK"

        /** Abre la lista de lotes por vencer. */
        const val ACTION_OPEN_EXPIRING = "io.github.yesidrobayo.shopledger.action.OPEN_EXPIRING"

        /** Abre los gastos recurrentes. */
        const val ACTION_OPEN_RECURRING = "io.github.yesidrobayo.shopledger.action.OPEN_RECURRING"

        /** Abre `Metas y límites`. */
        const val ACTION_OPEN_LIMITS = "io.github.yesidrobayo.shopledger.action.OPEN_LIMITS"

        /** El id del producto que abre [ACTION_OPEN_PRODUCT]. */
        const val EXTRA_PRODUCT_ID = "product_id"

        /**
         * El pedido que trae [intent], o `null` si no trae uno conocido y válido. Nunca lanza: un
         * extra que no se puede leer, como una clase que esta app no tiene, cuenta como ninguno.
         */
        fun readFrom(intent: Intent?): AppOpenRequest? {
            val action = intent?.action ?: return null
            return try {
                when (action) {
                    ACTION_OPEN_PRODUCT ->
                        intent
                            .getLongExtra(EXTRA_PRODUCT_ID, 0L)
                            .takeIf { it > 0L }
                            ?.let(::Product)

                    ACTION_OPEN_LOW_STOCK -> LowStock

                    ACTION_OPEN_EXPIRING -> Expiring

                    ACTION_OPEN_RECURRING -> Recurring

                    ACTION_OPEN_LIMITS -> GoalsAndLimits

                    else -> null
                }
            } catch (_: RuntimeException) {
                // Los extras de otra app pueden traer algo que no se deja leer: no abre nada.
                null
            }
        }
    }
}
