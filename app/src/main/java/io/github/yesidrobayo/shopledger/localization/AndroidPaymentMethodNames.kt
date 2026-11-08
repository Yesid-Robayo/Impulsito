package io.github.yesidrobayo.shopledger.localization

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames

/**
 * El nombre visible de los métodos de pago iniciales, de los recursos de la app y en el idioma de
 * la interfaz: es el nombre que una venta congela cuando cobra con un método que nadie renombró.
 *
 * [resources] da los recursos en el idioma elegido para la app cada vez que se pide un nombre, así
 * que cambiar de idioma cambia el nombre de las ventas siguientes y no el de las ya guardadas. Una
 * clave que esta versión no conoce se devuelve tal cual, igual que en la lista de métodos.
 */
class AndroidPaymentMethodNames(private val resources: () -> Resources) : PaymentMethodNames {
    override fun systemName(systemKey: String): String =
        SeedText.displayName(resources(), SeedTable.PAYMENT_METHODS, systemKey, name = null)
}
