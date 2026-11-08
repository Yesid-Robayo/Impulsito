package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las tasas sobre [InMemoryDatabase], con las mismas reglas que la base real: el nombre normalizado
 * no se repite entre las vigentes, y eliminar una tasa deja sin tasa a sus productos y al negocio,
 * como su llave foránea con `SET NULL`.
 */
internal class FakeTaxRateRepository(private val database: InMemoryDatabase) : TaxRateRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<TaxRate>> = database.tables
        .map { tables ->
            tables.taxRates.values
                .filter {
                    when (filter) {
                        ArchiveFilter.ACTIVE -> !it.isArchived
                        ArchiveFilter.ARCHIVED -> it.isArchived
                    }
                }
                .sortedBy { it.id }
        }
        .distinctUntilChanged()

    override fun observeProductCounts(): Flow<Map<Long, Int>> = database.tables
        .map { tables -> tables.productTaxRateIds.values.groupingBy { it }.eachCount() }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): TaxRate? = database.read().taxRates[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): TaxRate? =
        database.read().taxRates.values.singleOrNull {
            !it.isArchived && it.details.normalizedName == normalizedName
        }

    override suspend fun countProducts(id: Long): Int =
        database.read().productTaxRateIds.values.count { it == id }

    override suspend fun insert(details: TaxRateDetails, createdAt: Instant): Long {
        check(findActiveByNormalizedName(details.normalizedName) == null) { "guard:tax_rates" }
        val id = database.nextId(TABLE)
        val rate = TaxRate(id, details, archivedAt = null, createdAt, createdAt)
        database.write { it.copy(taxRates = it.taxRates + (id to rate)) }
        return id
    }

    override suspend fun update(id: Long, details: TaxRateDetails, updatedAt: Instant) {
        val rate = checkNotNull(findById(id)) { "La tasa no existe" }
        val other = findActiveByNormalizedName(details.normalizedName)
        check(other == null || other.id == id) { "guard:tax_rates" }
        val changed = rate.copy(details = details, updatedAt = updatedAt)
        database.write { it.copy(taxRates = it.taxRates + (id to changed)) }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        val rate = checkNotNull(findById(id)) { "La tasa no existe" }
        if (archivedAt == null) {
            check(findActiveByNormalizedName(rate.details.normalizedName) == null) {
                "guard:tax_rates"
            }
        }
        val changed = rate.copy(archivedAt = archivedAt, updatedAt = updatedAt)
        database.write { it.copy(taxRates = it.taxRates + (id to changed)) }
    }

    override suspend fun delete(id: Long) {
        checkNotNull(findById(id)) { "La tasa no existe" }
        database.write { tables ->
            val settings = tables.taxSettings
            tables.copy(
                taxRates = tables.taxRates - id,
                productTaxRateIds = tables.productTaxRateIds.filterValues { it != id },
                taxSettings =
                    if (settings.defaultTaxRateId == id) {
                        settings.copy(defaultTaxRateId = null)
                    } else {
                        settings
                    },
            )
        }
    }

    /** Da la tasa [rateId] al producto [productId], como haría la pantalla de producto. */
    fun assignToProduct(productId: Long, rateId: Long) {
        database.begin()
        database.write { it.copy(productTaxRateIds = it.productTaxRateIds + (productId to rateId)) }
        database.commit(counted = false)
    }

    private companion object {
        const val TABLE = "tax_rates"
    }
}

/** Los ajustes de impuestos sobre [InMemoryDatabase]: se deshacen con la transacción. */
internal class FakeTaxSettingsRepository(private val database: InMemoryDatabase) :
    TaxSettingsRepository {
    /** Cuántas veces se escribieron. */
    var updates: Int = 0
        private set

    override fun observe(): Flow<TaxSettings> =
        database.tables.map { it.taxSettings }.distinctUntilChanged()

    override suspend fun get(): TaxSettings = database.read().taxSettings

    override suspend fun update(taxes: TaxSettings, updatedAt: Instant) {
        val default = taxes.defaultTaxRateId
        if (default != null) {
            val rate = checkNotNull(database.read().taxRates[default]) { "FOREIGN KEY" }
            check(!rate.isArchived) { "La tasa por defecto debe estar vigente" }
        }
        database.write { it.copy(taxSettings = taxes) }
        updates++
    }
}
