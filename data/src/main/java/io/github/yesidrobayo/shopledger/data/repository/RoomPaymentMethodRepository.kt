package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.payment.PaymentMethodEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los métodos de pago sobre `payment_methods`.
 *
 * Un tipo guardado que esta versión no conoce se lee como `OTHER`, y no como fila rota: los
 * métodos son datos de la persona, y una copia de una versión más nueva puede traer uno. Al guardar
 * el método, se guarda ya como `OTHER`. Lo demás que sus guardas no admiten sí es una fila rota.
 *
 * La base impone lo mismo que el caso de uso revisa antes: el nombre no se repite entre los
 * vigentes, y un método con ventas no se borra por su llave `RESTRICT`. Si se rompe, sale como regla
 * rota, porque el caso de uso no debió pedirlo.
 */
internal class RoomPaymentMethodRepository(database: AppDatabase) : PaymentMethodRepository {
    private val methods = database.paymentMethodDao()
    private val accounts = database.moneyAccountDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla, aunque lo leído sea igual: una lista
    // igual a la anterior no se repite.
    override fun observe(filter: ArchiveFilter): Flow<List<PaymentMethodRecord>> {
        val rows =
            when (filter) {
                ArchiveFilter.ACTIVE -> methods.observeActive()
                ArchiveFilter.ARCHIVED -> methods.observeArchived()
            }
        return rows.map { list -> list.map(::toMethod) }.distinctUntilChanged()
    }

    override suspend fun findActive(): List<PaymentMethodRecord> =
        methods.findActive().map(::toMethod)

    override suspend fun findById(id: Long): PaymentMethodRecord? =
        methods.findById(id)?.let(::toMethod)

    override suspend fun findActiveByNormalizedName(normalizedName: String): PaymentMethodRecord? =
        methods.findActiveByNormalizedName(normalizedName)?.let(::toMethod)

    override suspend fun hasSalePayments(id: Long): Boolean = methods.hasSalePayments(id)

    override suspend fun isActiveMoneyAccount(id: Long): Boolean {
        val account = accounts.findById(id)
        return account != null && account.archivedAt == null
    }

    override suspend fun maxSortOrder(): Long? = methods.maxSortOrder()

    override suspend fun insert(
        details: PaymentMethodDetails,
        sortOrder: Long,
        createdAt: Instant,
    ): Long = writes.inTransaction {
        val at = createdAt.toStoredMillis()
        methods.insert(
            PaymentMethodEntity(
                // Solo los iniciales, que crea la base al nacer, llevan clave.
                systemKey = null,
                name = details.name,
                normalizedName = details.normalizedName,
                kind = details.kind.name,
                feeBp = details.feeRate.bp,
                feeFixed = details.feeFixed.minor,
                settlementDays = details.settlementDays.toLong(),
                priceAdjustmentBp = details.priceAdjustment.bp,
                moneyAccountId = details.moneyAccountId,
                instructionsText = details.instructionsText,
                instructionsMediaId = details.instructionsMediaId,
                sortOrder = sortOrder,
                archivedAt = null,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun update(id: Long, details: PaymentMethodDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                methods.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    kind = details.kind.name,
                    feeBp = details.feeRate.bp,
                    feeFixed = details.feeFixed.minor,
                    settlementDays = details.settlementDays.toLong(),
                    priceAdjustmentBp = details.priceAdjustment.bp,
                    moneyAccountId = details.moneyAccountId,
                    instructionsText = details.instructionsText,
                    instructionsMediaId = details.instructionsMediaId,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setSortOrder(id: Long, sortOrder: Long, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(methods.updateSortOrder(id, sortOrder, updatedAt.toStoredMillis()))
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                methods.setArchivedAt(id, archivedAt?.toStoredMillis(), updatedAt.toStoredMillis()),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(methods.delete(id)) }
    }

    private fun toMethod(row: PaymentMethodEntity): PaymentMethodRecord {
        // Sin nombre ni clave no habría con qué mostrarlo, y el nombre va con su forma normalizada:
        // el modelo los rechaza, y aquí salen como fila rota.
        if (row.name == null && row.systemKey == null) brokenRow()
        if ((row.name == null) != (row.normalizedName == null)) brokenRow()
        return PaymentMethodRecord(
            id = row.id,
            systemKey = row.systemKey,
            details =
                PaymentMethodDetails(
                    name = row.name,
                    normalizedName = row.normalizedName,
                    kind = PaymentMethodKind.fromStoredOrOther(row.kind),
                    feeRate = BasisPoints(row.feeBp),
                    feeFixed = Money(row.feeFixed),
                    settlementDays = row.settlementDays.toStoredInt(),
                    priceAdjustment = BasisPoints(row.priceAdjustmentBp),
                    moneyAccountId = row.moneyAccountId,
                    instructionsText = row.instructionsText,
                    instructionsMediaId = row.instructionsMediaId,
                ),
            sortOrder = row.sortOrder,
            archivedAt = row.archivedAt?.toInstant(),
            createdAt = row.createdAt.toInstant(),
            updatedAt = row.updatedAt.toInstant(),
        )
    }
}
