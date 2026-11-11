package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.payment.PaymentMethodEntity
import io.github.yesidrobayo.shopledger.data.local.payment.SalePaymentEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentMethod
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los pagos de las ventas sobre `sale_payments`, y lo que una venta lee de `payment_methods`. Un
 * pago solo se agrega: sus guardas impiden editarlo o borrarlo.
 *
 * Un tipo de método desconocido, que puede llegar en una copia de una versión más nueva, se lee
 * como otro medio y no daña la fila: los métodos son datos de la persona, no una lista cerrada.
 */
internal class RoomSalePaymentRepository(database: AppDatabase) : SalePaymentRepository {
    private val methods = database.paymentMethodDao()
    private val payments = database.salePaymentDao()
    private val writes = RepositoryWrites(database)

    override suspend fun findMethod(id: Long): SalePaymentMethod? =
        methods.findById(id)?.let(::toMethod)

    override fun observeBySale(saleId: Long): Flow<List<SalePayment>> = payments
        .observeBySale(saleId)
        .map { rows -> rows.map(::toPayment) }
        .distinctUntilChanged()

    override suspend fun findBySale(saleId: Long): List<SalePayment> =
        payments.findBySale(saleId).map(::toPayment)

    override suspend fun insert(saleId: Long, payment: NewSalePayment): Long =
        writes.inTransaction {
            payments.insert(
                SalePaymentEntity(
                    saleId = saleId,
                    paymentMethodId = payment.paymentMethodId,
                    methodNameSnapshot = payment.methodNameSnapshot,
                    methodKindSnapshot = payment.methodKind.name,
                    amount = payment.amount.minor,
                    feeAmount = payment.feeAmount.minor,
                    settlementDate = payment.settlementDate.toStored(),
                    moneyAccountId = payment.moneyAccountId,
                    tenderedAmount = null,
                    changeAmount = null,
                    tenderCurrencyCode = null,
                    tenderCurrencyDecimals = null,
                    tenderAmount = null,
                    fxRateMicro = null,
                    createdAt = payment.createdAt.toStoredMillis(),
                ),
            )
        }

    private fun toMethod(row: PaymentMethodEntity): SalePaymentMethod = SalePaymentMethod(
        id = row.id,
        systemKey = row.systemKey,
        name = row.name,
        kind = kindOf(row.kind),
        feeRate = BasisPoints(row.feeBp),
        feeFixed = Money(row.feeFixed),
        settlementDays = row.settlementDays.toStoredInt(),
        moneyAccountId = row.moneyAccountId,
        isArchived = row.archivedAt != null,
        priceAdjustment = BasisPoints(row.priceAdjustmentBp),
    )

    private fun toPayment(row: SalePaymentEntity): SalePayment = SalePayment(
        id = row.id,
        saleId = row.saleId,
        content = NewSalePayment(
            paymentMethodId = row.paymentMethodId,
            methodNameSnapshot = row.methodNameSnapshot,
            methodKind = kindOf(row.methodKindSnapshot),
            amount = Money(row.amount),
            feeAmount = Money(row.feeAmount),
            settlementDate = storedDate(row.settlementDate),
            moneyAccountId = row.moneyAccountId,
            createdAt = row.createdAt.toInstant(),
        ),
    )

    private fun kindOf(stored: String): PaymentMethodKind =
        PaymentMethodKind.fromStoredOrNull(stored) ?: PaymentMethodKind.OTHER
}
