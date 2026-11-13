package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los clientes sobre `customers`. Los saldos no se escriben aquí: nacen en cero y los mueve la
 * escritura de sus libros. Nada de lo que la persona escribe de un cliente sale de esta clase hacia
 * un registro: las fallas salen tipadas, sin el texto de SQLite.
 */
internal class RoomCustomerRepository(database: AppDatabase) : CustomerRepository {
    private val customers = database.customerDao()
    private val orders = database.orderDao()
    private val writes = RepositoryWrites(database)

    override fun observe(filter: ArchiveFilter): Flow<List<Customer>> {
        val rows =
            when (filter) {
                ArchiveFilter.ACTIVE -> customers.observeUnarchived()
                ArchiveFilter.ARCHIVED -> customers.observeArchived()
            }
        return rows.map { list -> list.map(::toCustomer) }.distinctUntilChanged()
    }

    override fun observeSelectable(): Flow<List<Customer>> =
        customers.observeActive().map { list -> list.map(::toCustomer) }.distinctUntilChanged()

    override fun observePointsSummary(): Flow<PointsSummary> = customers.observePointsSummary()
        .map { PointsSummary(it.totalPoints, it.customers.toStoredInt()) }
        .distinctUntilChanged()

    override fun observeById(id: Long): Flow<Customer?> =
        customers.observeById(id).map { row -> row?.let(::toCustomer) }.distinctUntilChanged()

    override suspend fun findById(id: Long): Customer? = customers.findById(id)?.let(::toCustomer)

    override suspend fun findActiveByNormalizedName(normalizedName: String): List<Customer> =
        customers.findActiveByNormalizedName(normalizedName).map(::toCustomer)

    override suspend fun hasHistory(id: Long): Boolean = customers.hasHistory(id)

    override suspend fun insert(details: CustomerDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            val contact = details.contact
            customers.insert(
                CustomerEntity(
                    name = contact.name,
                    normalizedName = contact.normalizedName,
                    phone = contact.phone,
                    email = contact.email,
                    notes = contact.notes,
                    priceListId = details.priceListId,
                    balance = 0L,
                    pointsBalance = 0L,
                    anonymizedAt = null,
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    /** Un cliente anonimizado no cambia: la sentencia no lo toca, y sale como regla rota. */
    override suspend fun update(id: Long, details: CustomerDetails, updatedAt: Instant) {
        writes.inTransaction {
            val contact = details.contact
            requireOneRow(
                customers.update(
                    id = id,
                    name = contact.name,
                    normalizedName = contact.normalizedName,
                    phone = contact.phone,
                    email = contact.email,
                    notes = contact.notes,
                    priceListId = details.priceListId,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                customers.setArchivedAt(
                    id,
                    archivedAt?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    /**
     * El cliente y la entrega de sus pedidos, en la misma transacción. Uno ya anonimizado no cambia,
     * y sale como regla rota: el caso de uso lo pregunta antes.
     */
    override suspend fun anonymize(id: Long, anonymizedAt: Instant) {
        writes.inTransaction {
            val at = anonymizedAt.toStoredMillis()
            requireOneRow(customers.anonymize(id, at))
            orders.clearDeliveryOf(id, at)
        }
    }

    /**
     * Con historia, una llave `RESTRICT` rechaza el borrado y sale como regla rota: el caso de uso
     * pregunta [hasHistory] antes y ofrece archivar. Una venta en espera queda sin cliente.
     */
    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(customers.delete(id)) }
    }

    /**
     * Un cliente anonimizado, o sin nombre, sale sin datos personales: así se muestra como cliente
     * anónimo aunque una copia restaurada traiga algún dato suelto.
     */
    private fun toCustomer(row: CustomerEntity): Customer {
        val name = row.name
        val contact =
            if (row.anonymizedAt != null || name == null) {
                null
            } else {
                CustomerContact(
                    name = name,
                    normalizedName = row.normalizedName ?: name,
                    phone = row.phone,
                    email = row.email,
                    notes = row.notes,
                )
            }
        return Customer(
            id = row.id,
            contact = contact,
            priceListId = row.priceListId,
            balance = Money(row.balance),
            pointsBalance = row.pointsBalance,
            anonymizedAt = row.anonymizedAt?.toInstant(),
            archivedAt = row.archivedAt?.toInstant(),
            createdAt = row.createdAt.toInstant(),
            updatedAt = row.updatedAt.toInstant(),
        )
    }
}
