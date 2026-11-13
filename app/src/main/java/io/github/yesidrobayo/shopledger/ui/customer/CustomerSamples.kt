package io.github.yesidrobayo.shopledger.ui.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant

/**
 * Clientes inventados para las vistas previas y la galería: un nombre de 80 caracteres, uno que
 * debe un monto largo, uno con saldo a favor, uno al día, un anonimizado y un archivado. Ningún dato
 * es de una persona real.
 */
object CustomerSamples {
    const val LONG_NAME: String =
        "Comercializadora de Abarrotes y Productos de Aseo del Barrio Central, Número 221"

    const val NOTES: String = "Paga los viernes. Prefiere que se le avise por mensaje."

    private val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    fun customer(
        id: Long,
        name: String?,
        balance: Money = Money.ZERO,
        phone: String? = null,
        points: Long = 0,
        archived: Boolean = false,
    ): Customer = Customer(
        id = id,
        contact =
            name?.let {
                CustomerContact(
                    name = it,
                    normalizedName = TextNormalizer.normalize(it),
                    phone = phone,
                    email = null,
                    notes = null,
                )
            },
        priceListId = null,
        balance = balance,
        pointsBalance = points,
        anonymizedAt = if (name == null) AT else null,
        archivedAt = if (archived) AT else null,
        createdAt = AT,
        updatedAt = AT,
    )

    val list: List<Customer> = listOf(
        customer(1, "Ana Ríos", Money(4_000), phone = "300 000 000"),
        customer(2, "Bruno Paz", Money(-1_500), points = 12),
        customer(3, "Carla Mena"),
        customer(4, LONG_NAME, Money(1_234_567_890_123L), phone = "+00 000 000 0000"),
        customer(5, "Dani Sol", Money(22_000), archived = true),
        customer(6, null, Money(8_000)),
    )
}
