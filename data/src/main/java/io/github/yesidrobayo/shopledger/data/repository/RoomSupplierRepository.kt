package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los proveedores sobre `suppliers`. El saldo no se escribe aquí: nace en cero y lo mueve la
 * escritura de su libro.
 */
internal class RoomSupplierRepository(database: AppDatabase) : SupplierRepository {
    private val suppliers = database.supplierDao()
    private val writes = RepositoryWrites(database)

    override fun observe(filter: ArchiveFilter): Flow<List<Supplier>> {
        val rows =
            when (filter) {
                ArchiveFilter.ACTIVE -> suppliers.observeActive()
                ArchiveFilter.ARCHIVED -> suppliers.observeArchived()
            }
        return rows.map { list -> list.map(::toSupplier) }.distinctUntilChanged()
    }

    override fun observeById(id: Long): Flow<Supplier?> =
        suppliers.observeById(id).map { row -> row?.let(::toSupplier) }.distinctUntilChanged()

    override suspend fun findById(id: Long): Supplier? = suppliers.findById(id)?.let(::toSupplier)

    override suspend fun findActiveByNormalizedName(normalizedName: String): Supplier? =
        suppliers.findActiveByNormalizedName(normalizedName)?.let(::toSupplier)

    override suspend fun hasHistory(id: Long): Boolean = suppliers.hasHistory(id)

    override suspend fun insert(details: SupplierDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            suppliers.insert(
                SupplierEntity(
                    name = details.name,
                    normalizedName = details.normalizedName,
                    phone = details.phone,
                    email = details.email,
                    notes = details.notes,
                    balance = 0L,
                    paymentTermsDays = details.paymentTermsDays?.toLong(),
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(id: Long, details: SupplierDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                suppliers.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    phone = details.phone,
                    email = details.email,
                    notes = details.notes,
                    paymentTermsDays = details.paymentTermsDays?.toLong(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                suppliers.setArchivedAt(
                    id,
                    archivedAt?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    /**
     * Con historia, una llave `RESTRICT` rechaza el borrado y sale como regla rota: el caso de uso
     * pregunta [hasHistory] antes y ofrece archivar.
     */
    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(suppliers.delete(id)) }
    }
}

/** Un proveedor guardado, tal como lo lee el dominio. */
internal fun toSupplier(row: SupplierEntity): Supplier = Supplier(
    id = row.id,
    details = SupplierDetails(
        name = row.name,
        normalizedName = row.normalizedName,
        phone = row.phone,
        email = row.email,
        notes = row.notes,
        paymentTermsDays = row.paymentTermsDays?.toStoredInt(),
    ),
    balance = Money(row.balance),
    archivedAt = row.archivedAt?.toInstant(),
    createdAt = row.createdAt.toInstant(),
    updatedAt = row.updatedAt.toInstant(),
)
