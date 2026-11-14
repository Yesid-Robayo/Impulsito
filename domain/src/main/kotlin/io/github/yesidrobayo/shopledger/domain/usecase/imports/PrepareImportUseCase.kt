package io.github.yesidrobayo.shopledger.domain.usecase.imports

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCatalog
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportChoice
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.ImportSeedLabels
import io.github.yesidrobayo.shopledger.domain.port.PriceIndexRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportNumberFormat
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportRowReader
import kotlinx.coroutines.flow.first

/**
 * Arma la vista previa de una importación: lee lo que ya existe en el negocio y revisa cada fila
 * del archivo con las reglas de su campo. No escribe nada, así que puede repetirse cada vez que la
 * persona cambia una columna o el separador decimal.
 *
 * - **Lo que ya existe** se reconoce por el nombre normalizado. Un producto o un proveedor
 *   archivado se omite siempre, sin reactivarlo. Un cliente se puede repetir de nombre: si hay
 *   más de uno vigente con ese nombre, no se sabe cuál actualizar y la fila se omite.
 * - **Los montos** se leen con los decimales de la moneda del negocio: más decimales es error de
 *   fila.
 * - **Las unidades y las listas** se reconocen por su nombre o su símbolo, en cualquier idioma de
 *   la app si son las iniciales.
 */
class PrepareImportUseCase(
    private val products: ProductRepository,
    private val customers: CustomerRepository,
    private val suppliers: SupplierRepository,
    private val categories: CategoryRepository,
    private val units: UnitRepository,
    private val priceLists: PriceListRepository,
    private val priceIndex: PriceIndexRepository,
    private val systemRows: SystemRowRepository,
    private val settings: AppSettingsRepository,
    private val seedLabels: ImportSeedLabels,
) {
    /**
     * La vista previa de [sheet] como [target], con [columns], el dato de cada columna o `null`,
     * y [decimal], el separador decimal de un CSV.
     */
    suspend operator fun invoke(
        sheet: ImportSheet,
        target: ImportTarget,
        columns: List<String?>,
        decimal: DecimalSeparator,
    ): ImportPreview {
        val format = ImportNumberFormat(
            decimal = decimal,
            currencyDecimals = settings.get().regional.currency.decimals,
            spreadsheet = sheet.separator == null,
        )
        return ImportRowReader(target, columns, format, catalogFor(target)).read(sheet)
    }

    private suspend fun catalogFor(target: ImportTarget): ImportCatalog = when (target) {
        ImportTarget.PRODUCTS -> productCatalog()

        ImportTarget.CUSTOMERS -> ImportCatalog(
            existing = customerNames(),
            priceLists = priceListChoices(),
        )

        ImportTarget.SUPPLIERS -> ImportCatalog(existing = supplierNames())

        ImportTarget.PRICE_INDEX ->
            ImportCatalog(existing = priceIndex.months().associateWith { false })
    }

    private suspend fun productCatalog(): ImportCatalog {
        val active = products.observe(ArchiveFilter.ACTIVE).first()
        val archived = products.observe(ArchiveFilter.ARCHIVED).first()
        val existing = HashMap<String, Boolean>()
        archived.forEach { existing[it.details.normalizedName] = true }
        active.forEach { existing[it.details.normalizedName] = false }
        val defaultUnitId = systemRows.findId(SystemRow.DEFAULT_UNIT)
        val available = units.observeActive().first()
        return ImportCatalog(
            existing = existing,
            barcodes = active.mapNotNull { product ->
                product.details.barcode?.let { it to product.details.normalizedName }
            }.toMap(),
            units = available.map { unit ->
                val seed = unit.systemKey?.let { seedLabels.names(SeedTable.UNITS, it) }.orEmpty()
                ImportChoice(
                    id = unit.id,
                    names = normalized(listOfNotNull(unit.name, unit.symbol) + seed),
                    allowsFraction = unit.allowsFraction,
                )
            },
            defaultUnitAllowsFraction =
                available.firstOrNull { it.id == defaultUnitId }?.allowsFraction ?: false,
            categories = categories.observeAll().first().mapTo(mutableSetOf()) {
                it.details.normalizedName
            },
            suppliers = supplierNames().filterValues { archived -> !archived }.keys,
        )
    }

    /** Los clientes vigentes por nombre: con dos del mismo nombre, no se actualiza ninguno. */
    private suspend fun customerNames(): Map<String, Boolean> =
        customers.observe(ArchiveFilter.ACTIVE).first()
            .mapNotNull { it.contact?.normalizedName }
            .groupingBy { it }
            .eachCount()
            .mapValues { (_, count) -> count > 1 }

    private suspend fun supplierNames(): Map<String, Boolean> {
        val names = HashMap<String, Boolean>()
        suppliers.observe(ArchiveFilter.ARCHIVED).first().forEach {
            names[it.details.normalizedName] = true
        }
        suppliers.observe(ArchiveFilter.ACTIVE).first().forEach {
            names[it.details.normalizedName] = false
        }
        return names
    }

    private suspend fun priceListChoices(): List<ImportChoice> =
        priceLists.observeActive().first().map { list ->
            val seed =
                list.systemKey?.let { seedLabels.names(SeedTable.PRICE_LISTS, it) }.orEmpty()
            ImportChoice(id = list.id, names = normalized(listOfNotNull(list.name) + seed))
        }

    private fun normalized(names: List<String>): Set<String> =
        names.map(TextNormalizer::normalize).filter(String::isNotEmpty).toSet()
}
