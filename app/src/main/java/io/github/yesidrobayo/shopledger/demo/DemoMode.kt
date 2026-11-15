package io.github.yesidrobayo.shopledger.demo

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.data.demo.DemoEntry
import io.github.yesidrobayo.shopledger.data.demo.DemoSession
import io.github.yesidrobayo.shopledger.data.demo.DemoTexts
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import kotlinx.coroutines.flow.StateFlow

/**
 * El modo demostración para la interfaz: entrar desde la bienvenida, saber si este proceso lo
 * muestra y salir. Entrar y salir solo preparan; quien llama reinicia la app después, como tras una
 * restauración, para que ninguna pantalla conserve datos de la otra base.
 *
 * Los nombres del negocio de ejemplo salen de los recursos en el idioma de la interfaz en el
 * momento de entrar: el negocio de ejemplo habla el idioma que eligió la persona.
 *
 * @param resources los recursos en el idioma de la interfaz.
 * @param methodNames el nombre de los métodos de pago iniciales, que una venta congela.
 */
class DemoMode(
    private val session: DemoSession,
    private val resources: () -> Resources,
    private val methodNames: PaymentMethodNames,
) {
    /** Si este proceso muestra la demostración, para la banda fija. */
    val active: StateFlow<Boolean> get() = session.active

    /** Si este proceso muestra la demostración. Fuera del hilo principal la primera vez. */
    suspend fun isActive(): Boolean = session.isActive()

    /**
     * Prepara la base de ejemplo. `true` si quedó lista y hay que reiniciar; `false` si no se pudo,
     * y entonces los datos del negocio no cambiaron.
     */
    suspend fun start(): Boolean = when (session.enter(texts(resources()))) {
        DemoEntry.Ready, DemoEntry.AlreadyActive -> true
        DemoEntry.BusinessUnavailable, DemoEntry.CurrencyMissing, DemoEntry.Failed -> false
    }

    /** Sale de la demostración. `true` si hay que reiniciar; `false` si sigue en ella. */
    suspend fun stop(): Boolean = session.exit()

    private fun texts(resources: Resources): DemoTexts = DemoTexts(
        businessName = resources.getString(R.string.demo_business_name),
        products = PRODUCTS.map { resources.getString(it) },
        customer = resources.getString(R.string.demo_customer),
        supplier = resources.getString(R.string.demo_supplier),
        expense = resources.getString(R.string.demo_expense),
        paymentMethodNames = methodNames,
    )

    private companion object {
        /** Los productos de ejemplo, en el orden en que los escribe la capa de datos. */
        val PRODUCTS = listOf(
            R.string.demo_product_rice,
            R.string.demo_product_oil,
            R.string.demo_product_eggs,
            R.string.demo_product_coffee,
            R.string.demo_product_cheese,
            R.string.demo_product_soap,
        )
    }
}
