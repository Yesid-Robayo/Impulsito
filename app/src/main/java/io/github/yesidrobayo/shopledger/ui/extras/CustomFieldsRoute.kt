package io.github.yesidrobayo.shopledger.ui.extras

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveCustomFieldUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteCustomFieldUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteTagUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ExtraLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveExtrasCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReorderCustomFieldsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCustomFieldUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveExtraResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveTagUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import kotlinx.coroutines.flow.Flow

/**
 * La pantalla de campos propios y etiquetas en la navegación. Atrás con una hoja abierta la
 * cierra; sin hoja, vuelve a donde se abrió. Mientras la base abre, o si no abre, la pantalla de
 * reserva de la navegación.
 */
@Composable
fun CustomFieldsRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            CustomFieldsViewModel(CustomFieldsUseCases.of(dependencies))
        }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable ->
            PlaceholderRoute(AppNavKey.CustomFields, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val editor by viewModel.editor.editor.collectAsStateWithLifecycle()
            BackHandler(enabled = editor != null) {
                viewModel.editor.onAction(ExtraEditorAction.Dismiss)
            }
            CustomFieldsScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == CustomFieldsAction.Back) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
                editor = editor,
                onEditorAction = viewModel.editor::onAction,
            )
        }
    }
}

/** Las operaciones de la pantalla sobre los casos de uso de campos y etiquetas. */
class CustomFieldsUseCases(
    private val observeCatalog: ObserveExtrasCatalogUseCase,
    private val saveFieldUseCase: SaveCustomFieldUseCase,
    private val archiveFieldUseCase: ArchiveCustomFieldUseCase,
    private val deleteFieldUseCase: DeleteCustomFieldUseCase,
    private val reorderUseCase: ReorderCustomFieldsUseCase,
    private val saveTagUseCase: SaveTagUseCase,
    private val deleteTagUseCase: DeleteTagUseCase,
) : CustomFieldsOperations {
    override fun catalog(): Flow<Pair<List<CustomField>, List<ProductTag>>> = observeCatalog()

    override suspend fun saveField(editingId: Long?, draft: CustomFieldDraft): SaveExtraResult =
        saveFieldUseCase(editingId, draft)

    override suspend fun archiveField(id: Long, archive: Boolean): ExtraLifecycleResult =
        archiveFieldUseCase(id, archive)

    override suspend fun deleteField(id: Long): ExtraLifecycleResult = deleteFieldUseCase(id)

    override suspend fun reorderFields(orderedIds: List<Long>): ExtraLifecycleResult =
        reorderUseCase(orderedIds)

    override suspend fun saveTag(editingId: Long?, name: String): SaveExtraResult =
        saveTagUseCase(editingId, name)

    override suspend fun deleteTag(id: Long): ExtraLifecycleResult = deleteTagUseCase(id)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): CustomFieldsUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            return CustomFieldsUseCases(
                observeCatalog = ObserveExtrasCatalogUseCase(ports.customFields, ports.tags),
                saveFieldUseCase = SaveCustomFieldUseCase(tx, ports.customFields, clock),
                archiveFieldUseCase = ArchiveCustomFieldUseCase(tx, ports.customFields, clock),
                deleteFieldUseCase = DeleteCustomFieldUseCase(tx, ports.customFields),
                reorderUseCase = ReorderCustomFieldsUseCase(tx, ports.customFields, clock),
                saveTagUseCase = SaveTagUseCase(tx, ports.tags, clock),
                deleteTagUseCase = DeleteTagUseCase(tx, ports.tags),
            )
        }
    }
}
