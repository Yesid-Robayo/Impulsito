package io.github.yesidrobayo.shopledger.ui.unit

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitsOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveConversionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveUnitsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UnitLifecycleResult
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlinx.coroutines.flow.Flow

/**
 * La pantalla de unidades y conversiones en la navegación. Atrás con una hoja abierta la cierra;
 * sin hoja, vuelve a donde se abrió. Mientras la base abre, o si no abre, la pantalla de reserva de
 * la navegación.
 */
@Composable
fun UnitsRoute(navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val resources = LocalResources.current
    val numbers =
        ProductNumberFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
        )
    val model =
        rememberAppViewModel { dependencies ->
            UnitsViewModel(UnitsUseCases.of(dependencies), ResourceUnitNames(resources), numbers)
        }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Units, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val unitEditor by viewModel.unitEditor.editor.collectAsStateWithLifecycle()
            val conversionEditor by viewModel.conversionEditor.editor.collectAsStateWithLifecycle()
            // Una hoja abierta como panel no se cierra sola con atrás: atrás la cierra primero.
            BackHandler(enabled = unitEditor != null) {
                viewModel.unitEditor.onAction(UnitEditorAction.Dismiss)
            }
            BackHandler(enabled = conversionEditor != null) {
                viewModel.conversionEditor.onAction(ConversionEditorAction.Dismiss)
            }
            UnitsScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == UnitsAction.Back) navigator.back() else viewModel.onAction(action)
                },
                unitEditor = unitEditor,
                onUnitEditorAction = viewModel.unitEditor::onAction,
                conversionEditor = conversionEditor,
                onConversionEditorAction = viewModel.conversionEditor::onAction,
            )
        }
    }
}

/** Los textos de las unidades con los recursos del idioma de la interfaz. */
internal class ResourceUnitNames(private val resources: Resources) : UnitNames {
    override fun name(unit: MeasureUnit): String =
        SeedText.displayName(resources, SeedTable.UNITS, unit.systemKey, unit.name)

    override fun symbol(unit: MeasureUnit): String? =
        SeedText.unitSymbol(resources, unit.systemKey, unit.symbol)
}

/** Las operaciones de la pantalla sobre los casos de uso de catálogo. */
class UnitsUseCases(
    private val observeUnits: ObserveUnitsUseCase,
    private val observeConversions: ObserveConversionsUseCase,
    private val observeProducts: () -> Flow<List<Product>>,
    private val saveUnitUseCase: SaveUnitUseCase,
    private val archive: ArchiveUnitUseCase,
    private val reactivate: ReactivateUnitUseCase,
    private val delete: DeleteUnitUseCase,
    private val saveConversionUseCase: SaveConversionUseCase,
    private val deleteConversionUseCase: DeleteConversionUseCase,
) : UnitsOperations {
    override fun units(): Flow<UnitsOverview> = observeUnits()

    override fun conversions(): Flow<List<ConversionEntry>> = observeConversions()

    override fun products(): Flow<List<Product>> = observeProducts()

    override suspend fun saveUnit(editingId: Long?, draft: UnitDraft): SaveUnitResult =
        saveUnitUseCase(editingId, draft)

    override suspend fun archiveUnit(id: Long): UnitLifecycleResult = archive(id)

    override suspend fun reactivateUnit(id: Long): UnitLifecycleResult = reactivate(id)

    override suspend fun deleteUnit(id: Long): UnitLifecycleResult = delete(id)

    override suspend fun saveConversion(
        editingId: Long?,
        draft: ConversionDraft,
    ): SaveConversionResult = saveConversionUseCase(editingId, draft)

    override suspend fun deleteConversion(id: Long): DeleteConversionResult =
        deleteConversionUseCase(id)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): UnitsUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            return UnitsUseCases(
                observeUnits = ObserveUnitsUseCase(ports.units, ports.unitCatalog),
                observeConversions = ObserveConversionsUseCase(ports.productUnits, ports.products),
                observeProducts = { ports.products.observe(ArchiveFilter.ACTIVE) },
                saveUnitUseCase = SaveUnitUseCase(tx, ports.unitCatalog, clock),
                archive = ArchiveUnitUseCase(tx, ports.unitCatalog, clock),
                reactivate = ReactivateUnitUseCase(tx, ports.unitCatalog, clock),
                delete = DeleteUnitUseCase(tx, ports.unitCatalog),
                saveConversionUseCase =
                    SaveConversionUseCase(
                        tx,
                        ports.products,
                        ports.units,
                        ports.productUnits,
                        clock,
                    ),
                deleteConversionUseCase = DeleteConversionUseCase(tx, ports.productUnits),
            )
        }
    }
}
