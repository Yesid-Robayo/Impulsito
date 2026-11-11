package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDetails
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitCatalogRepository
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que escribe la pantalla de unidades, sobre [InMemoryDatabase], con las reglas de la guarda de
 * `units`: el nombre no se repite entre las vigentes, y la de clave `UNIT` no se archiva ni se borra.
 * Una unidad tiene uso si es base de un producto, unidad de una conversión o de alguna línea.
 */
internal class FakeUnitCatalogRepository(private val database: InMemoryDatabase) :
    UnitCatalogRepository {
    override fun observeInUse(): Flow<Set<Long>> =
        database.tables.map(::inUse).distinctUntilChanged()

    override suspend fun findById(id: Long): MeasureUnit? = database.read().units[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): MeasureUnit? =
        database.read().units.values.singleOrNull {
            !it.isArchived && it.name?.let(TextNormalizer::normalize) == normalizedName
        }

    override suspend fun isInUse(id: Long): Boolean = id in inUse(database.read())

    override suspend fun insert(details: UnitDetails, createdAt: Instant): Long {
        checkName(details.normalizedName, editingId = null)
        val id = database.nextId(TABLE)
        val unit =
            MeasureUnit(
                id = id,
                systemKey = null,
                name = details.name,
                symbol = details.symbol,
                kind = details.kind,
                allowsFraction = details.allowsFraction,
                archivedAt = null,
            )
        database.write { it.copy(units = it.units + (id to unit)) }
        return id
    }

    override suspend fun update(id: Long, details: UnitDetails, updatedAt: Instant) {
        val unit = checkNotNull(findById(id)) { "La unidad no existe" }
        checkName(details.normalizedName, editingId = id)
        val changed =
            unit.copy(
                name = details.name,
                symbol = details.symbol,
                kind = details.kind,
                allowsFraction = details.allowsFraction,
            )
        database.write { it.copy(units = it.units + (id to changed)) }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        val unit = checkNotNull(findById(id)) { "La unidad no existe" }
        check(unit.systemKey != "UNIT") { "guard:units" }
        database.write { it.copy(units = it.units + (id to unit.copy(archivedAt = archivedAt))) }
    }

    override suspend fun delete(id: Long) {
        val unit = checkNotNull(findById(id)) { "La unidad no existe" }
        check(unit.systemKey != "UNIT") { "guard:units_system" }
        check(!isInUse(id)) { "RESTRICT" }
        database.write { it.copy(units = it.units - id) }
    }

    private suspend fun checkName(normalizedName: String?, editingId: Long?) {
        val other = normalizedName?.let { findActiveByNormalizedName(it) }
        check(other == null || other.id == editingId) { "guard:units" }
    }

    private fun inUse(tables: Tables): Set<Long> =
        tables.products.values.map { it.details.baseUnitId }.toSet() +
            tables.productUnits.values.map { it.details.unitId } +
            tables.unitsUsedByLines

    private companion object {
        const val TABLE = "units"
    }
}

/**
 * Las conversiones sobre [InMemoryDatabase], con las reglas de la guarda de `product_units`: una por
 * producto y unidad, y un código que no se repite entre conversiones ni con otro producto vigente.
 */
internal class FakeProductUnitRepository(private val database: InMemoryDatabase) :
    ProductUnitRepository {
    override fun observeAll(): Flow<List<ProductConversion>> =
        database.tables.map { it.productUnits.values.sortedBy(ProductConversion::id) }

    override fun observeByProduct(productId: Long): Flow<List<ProductConversion>> =
        database.tables.map { tables ->
            tables.productUnits.values.filter { it.productId == productId }.sortedBy { it.id }
        }

    override suspend fun findById(id: Long): ProductConversion? = database.read().productUnits[id]

    override suspend fun findByProductAndUnit(productId: Long, unitId: Long): ProductConversion? =
        database.read().productUnits.values.singleOrNull {
            it.productId == productId && it.details.unitId == unitId
        }

    override suspend fun findByBarcode(barcode: String): ProductConversion? =
        database.read().productUnits.values.singleOrNull { it.details.barcode == barcode }

    override suspend fun insert(
        productId: Long,
        details: ConversionDetails,
        createdAt: Instant,
    ): Long {
        check(findByProductAndUnit(productId, details.unitId) == null) { "UNIQUE" }
        checkBarcode(details.barcode, productId, editingId = null)
        val id = database.nextId(TABLE)
        val conversion = ProductConversion(id, productId, details)
        database.write { it.copy(productUnits = it.productUnits + (id to conversion)) }
        return id
    }

    override suspend fun update(id: Long, details: ConversionDetails, updatedAt: Instant) {
        val conversion = checkNotNull(findById(id)) { "La conversión no existe" }
        check(details.unitId == conversion.details.unitId) { "La unidad no cambia" }
        checkBarcode(details.barcode, conversion.productId, editingId = id)
        val changed = conversion.copy(details = details)
        database.write { it.copy(productUnits = it.productUnits + (id to changed)) }
    }

    override suspend fun delete(id: Long) {
        checkNotNull(findById(id)) { "La conversión no existe" }
        database.write { it.copy(productUnits = it.productUnits - id) }
    }

    private fun checkBarcode(barcode: String?, productId: Long, editingId: Long?) {
        if (barcode == null) return
        val tables = database.read()
        val inProducts =
            tables.products.values.any {
                it.details.barcode == barcode && !it.isArchived && it.id != productId
            }
        val inConversions =
            tables.productUnits.values.any { it.details.barcode == barcode && it.id != editingId }
        check(!inProducts && !inConversions) { "guard:product_units" }
    }

    private companion object {
        const val TABLE = "product_units"
    }
}
