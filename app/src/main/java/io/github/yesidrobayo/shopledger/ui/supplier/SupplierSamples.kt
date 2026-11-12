package io.github.yesidrobayo.shopledger.ui.supplier

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierListEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierPurchase
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierTotals
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant

/**
 * Proveedores inventados para las vistas previas y la galería: un nombre de 80 caracteres, uno sin
 * teléfono, un archivado y un monto largo. Ningún dato es de una persona o negocio real.
 */
object SupplierSamples {
    const val LONG_NAME: String =
        "Distribuidora Mayorista de Abarrotes, Granos y Productos de Limpieza del Valle 2"

    const val NOTES: String =
        "Entrega martes y viernes antes del mediodía. Pedido mínimo de diez cajas."

    private val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val DATE: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-10"))

    fun supplier(
        id: Long,
        name: String,
        phone: String? = null,
        email: String? = null,
        notes: String? = null,
        archived: Boolean = false,
    ): Supplier = Supplier(
        id = id,
        details =
            SupplierDetails(
                name = name,
                normalizedName = TextNormalizer.normalize(name),
                phone = phone,
                email = email,
                notes = notes,
                paymentTermsDays = null,
            ),
        balance = Money.ZERO,
        archivedAt = if (archived) AT else null,
        createdAt = AT,
        updatedAt = AT,
    )

    val active: List<SupplierListEntry> =
        listOf(
            SupplierListEntry(supplier(1L, "Granja El Alto", phone = "+1 555 010 0199"), 4),
            SupplierListEntry(supplier(2L, LONG_NAME), 12),
            SupplierListEntry(supplier(3L, "Lácteos del Valle"), 0),
        )

    val archived: List<SupplierListEntry> =
        listOf(SupplierListEntry(supplier(4L, "Panadería La Esquina", archived = true), 0))

    fun overview(archived: Boolean = false, purchases: Boolean = true): SupplierOverview =
        SupplierOverview(
            supplier =
                supplier(
                    1L,
                    LONG_NAME,
                    phone = "+1 555 010 0199",
                    email = "compras@example.com",
                    notes = NOTES,
                    archived = archived,
                ),
            totals =
                if (purchases) {
                    SupplierTotals(3, Money(123_456_789_00L), hasHistory = true)
                } else {
                    SupplierTotals.NONE
                },
            purchases =
                if (purchases) {
                    listOf(
                        purchase(3L, 98_765_432_10L, DocumentStatus.ACTIVE, "F-2291"),
                        purchase(2L, 4_500_00L, DocumentStatus.VOIDED, null),
                        purchase(1L, 24_691_357_90L, DocumentStatus.ACTIVE, "F-2170"),
                    )
                } else {
                    emptyList()
                },
            usualProducts =
                listOf(
                    SupplierProduct(10L, "Arroz blanco 1 kg"),
                    SupplierProduct(11L, "Frijol rojo"),
                ),
        )

    private fun purchase(id: Long, total: Long, status: DocumentStatus, reference: String?) =
        SupplierPurchase(id, status, Money(total), reference, AT, DATE)
}
