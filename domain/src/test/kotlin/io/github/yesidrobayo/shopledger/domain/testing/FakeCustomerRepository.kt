package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Los clientes sobre [InMemoryDatabase], con las reglas de la base: un anonimizado no se edita, y
 * uno con historia no se borra.
 */
internal class FakeCustomerRepository(private val database: InMemoryDatabase) : CustomerRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<Customer>> =
        database.tables.map { tables ->
            sorted(
                tables.customers.values.filter {
                    it.isArchived ==
                        (filter == ArchiveFilter.ARCHIVED)
                },
            )
        }

    override fun observeSelectable(): Flow<List<Customer>> = database.tables.map { tables ->
        sorted(tables.customers.values.filter { !it.isArchived && !it.isAnonymized })
    }

    override fun observeById(id: Long): Flow<Customer?> =
        database.tables.map { tables -> tables.customers[id] }

    override suspend fun findById(id: Long): Customer? = database.read().customers[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): List<Customer> =
        database.read().customers.values
            .filter { !it.isArchived && it.contact?.normalizedName == normalizedName }
            .sortedBy { it.id }

    override suspend fun hasHistory(id: Long): Boolean = id in database.read().customersWithHistory

    override suspend fun insert(details: CustomerDetails, createdAt: Instant): Long {
        val id = database.nextId(TABLE)
        val stored = Customer(
            id = id,
            contact = details.contact,
            priceListId = details.priceListId,
            balance = Money.ZERO,
            pointsBalance = 0L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        database.write { it.copy(customers = it.customers + (id to stored)) }
        return id
    }

    override suspend fun update(id: Long, details: CustomerDetails, updatedAt: Instant) =
        change(id) {
            check(!it.isAnonymized) { "Un cliente anonimizado no se edita" }
            it.copy(
                contact = details.contact,
                priceListId = details.priceListId,
                updatedAt = updatedAt,
            )
        }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        change(id) { it.copy(archivedAt = archivedAt, updatedAt = updatedAt) }

    override suspend fun anonymize(id: Long, anonymizedAt: Instant) = change(id) {
        check(!it.isAnonymized) { "Ya estaba anonimizado" }
        it.copy(contact = null, anonymizedAt = anonymizedAt, updatedAt = anonymizedAt)
    }

    override suspend fun delete(id: Long) {
        // Como la base: con historia, una llave lo impide.
        check(!hasHistory(id)) { "Una llave impide borrarlo" }
        database.write { tables ->
            checkNotNull(tables.customers[id]) { "No existe el cliente" }
            tables.copy(customers = tables.customers - id)
        }
    }

    private fun change(id: Long, update: (Customer) -> Customer) {
        database.write { tables ->
            val customer = checkNotNull(tables.customers[id]) { "No existe el cliente" }
            tables.copy(customers = tables.customers + (id to update(customer)))
        }
    }

    /** Por nombre normalizado y luego por id, con los anonimizados al final, como la base. */
    private fun sorted(customers: Collection<Customer>): List<Customer> = customers.sortedWith(
        compareBy<Customer> { it.contact == null }
            .thenBy { it.contact?.normalizedName }
            .thenBy { it.id },
    )

    private companion object {
        const val TABLE = "customers"
    }
}
