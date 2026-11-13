package io.github.yesidrobayo.shopledger.ui.supplierreturn

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnDetail
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnResult
import io.github.yesidrobayo.shopledger.ui.product.CREATED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** El detalle de una devolución a proveedor sin base: leerla, anularla tras confirmar y fallar. */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierReturnDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val active = SupplierReturnDetail(
        purchaseReturn = PurchaseReturn(
            id = 12L,
            content = NewPurchaseReturn(
                purchaseId = 9L,
                supplierId = 5L,
                resolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
                total = Money(2_800L),
                moneyAccountId = null,
                note = null,
                occurredAt = CREATED,
                businessDate = BusinessDate(LocalDate(2026, 11, 2)),
                createdAt = CREATED,
            ),
            voiding = null,
        ),
        supplierName = "Molino",
        lines = emptyList(),
    )
    private val detail = MutableStateFlow<SupplierReturnDetail?>(active)
    private var result: VoidSupplierReturnResult = VoidSupplierReturnResult.Voided
    private var voids = 0

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        source: () -> Flow<SupplierReturnDetail?> = { detail },
    ): SupplierReturnDetailViewModel {
        val model = SupplierReturnDetailViewModel(source) {
            voids++
            if (result == VoidSupplierReturnResult.Voided) {
                detail.value = active.copy(
                    purchaseReturn = active.purchaseReturn.copy(
                        voiding = Voiding(CREATED, VoidReason.ENTRY_ERROR, null),
                    ),
                )
            }
            result
        }
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun SupplierReturnDetailViewModel.act(
        scope: TestScope,
        action: SupplierReturnDetailAction,
    ) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val SupplierReturnDetailViewModel.ready: SupplierReturnDetailUiState.Ready
        get() = uiState.value as SupplierReturnDetailUiState.Ready

    @Test
    fun `voiding asks first and then says it once`() = runTest(dispatcher) {
        val model = viewModel()
        assertTrue(model.ready.canVoid)

        model.act(this, SupplierReturnDetailAction.AskVoid)
        assertTrue(model.ready.confirmVoid)
        model.act(this, SupplierReturnDetailAction.DismissVoid)
        assertEquals(0, voids)
        model.act(this, SupplierReturnDetailAction.AskVoid)
        model.act(this, SupplierReturnDetailAction.ConfirmVoid)

        assertEquals(1, voids)
        assertTrue(model.ready.justVoided)
        assertTrue(!model.ready.canVoid)
        model.act(this, SupplierReturnDetailAction.VoidShown)
        assertTrue(!model.ready.justVoided)
        model.act(this, SupplierReturnDetailAction.Back)
    }

    @Test
    fun `a failed void keeps the return active and says so`() = runTest(dispatcher) {
        result = VoidSupplierReturnResult.StorageFailed(StorageFailure.IO_ERROR)
        val model = viewModel()

        model.act(this, SupplierReturnDetailAction.ConfirmVoid)

        assertTrue(model.ready.voidFailed)
        assertTrue(model.ready.canVoid)
    }

    @Test
    fun `a missing return and a failed read`() = runTest(dispatcher) {
        detail.value = null
        assertEquals(SupplierReturnDetailUiState.NotFound, viewModel().uiState.value)

        var fail = true
        val flaky = viewModel(source = {
            flow {
                if (fail) error("Lectura inventada que falla")
                emit(active)
            }
        })
        assertEquals(SupplierReturnDetailUiState.ReadError, flaky.uiState.value)
        fail = false
        flaky.act(this, SupplierReturnDetailAction.Retry)
        assertTrue(flaky.uiState.value is SupplierReturnDetailUiState.Ready)
    }
}
