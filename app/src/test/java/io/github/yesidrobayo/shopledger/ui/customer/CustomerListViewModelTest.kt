package io.github.yesidrobayo.shopledger.ui.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomersOverview
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadgeVariant
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** La lista de clientes y su hoja, con respuestas fijas del dominio. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeCustomerOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `the list shows the active customers and what is receivable`() = runTest(dispatcher) {
        val model = CustomerListViewModel(operations)
        observe(model.uiState)
        assertEquals(CustomerListContent.Loading, model.uiState.value.content)

        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), ids(state.content))
        assertEquals(Money(134_000), state.receivable)
        assertFalse(state.showPoints)
    }

    @Test
    fun `nobody owing shows no receivable card`() = runTest(dispatcher) {
        operations.overview.value = CustomersOverview(
            listOf(CustomerSamples.customer(1, "Ana", Money(-500))),
            emptyList(),
            receivable = Money.ZERO,
        )
        val model = CustomerListViewModel(operations)
        observe(model.uiState)
        advanceUntilIdle()

        assertNull(model.uiState.value.receivable)
    }

    @Test
    fun `points show only with the program on`() = runTest(dispatcher) {
        operations.loyalty.value = true
        val model = CustomerListViewModel(operations)
        observe(model.uiState)
        advanceUntilIdle()

        assertTrue(model.uiState.value.showPoints)
        assertEquals(12L, rowData(CustomerSamples.list[1], showPoints = true).points)
        assertNull(rowData(CustomerSamples.list[1], showPoints = false).points)
    }

    @Test
    fun `each filter keeps its customers, archived ones included where they count`() {
        val overview = sampleOverview()
        fun shown(filter: CustomerFilter) =
            ids(CustomerListViewModel.contentOf(overview, "", filter))

        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), shown(CustomerFilter.ALL))
        assertEquals(listOf(1L, 4L, 5L, 6L), shown(CustomerFilter.RECEIVABLE))
        assertEquals(listOf(2L), shown(CustomerFilter.CREDIT))
        assertEquals(listOf(5L), shown(CustomerFilter.ARCHIVED))
    }

    @Test
    fun `searching by name or phone digits, and no match offers to create it`() =
        runTest(dispatcher) {
            val model = CustomerListViewModel(operations)
            observe(model.uiState)

            model.onAction(CustomerListAction.QueryChange("RIOS"))
            advanceUntilIdle()
            assertEquals(listOf(1L), ids(model.uiState.value.content))

            model.onAction(CustomerListAction.QueryChange("300 000"))
            advanceUntilIdle()
            assertEquals(listOf(1L), ids(model.uiState.value.content))

            model.onAction(CustomerListAction.QueryChange(" Eva "))
            advanceUntilIdle()
            assertEquals(CustomerListContent.NoResults("Eva"), model.uiState.value.content)

            model.onAction(CustomerListAction.NewCustomer(" Eva "))
            assertEquals("Eva", model.editor.editor.value?.name)
        }

    @Test
    fun `an empty filter offers to clear it, and clearing goes back to all`() =
        runTest(dispatcher) {
            operations.overview.value = CustomersOverview(
                listOf(CustomerSamples.customer(1, "Ana")),
                emptyList(),
                receivable = Money.ZERO,
            )
            val model = CustomerListViewModel(operations)
            observe(model.uiState)
            model.onAction(CustomerListAction.FilterChange(CustomerFilter.CREDIT))
            advanceUntilIdle()
            assertEquals(CustomerListContent.FilterEmpty, model.uiState.value.content)

            model.onAction(CustomerListAction.QueryChange("ana"))
            model.onAction(CustomerListAction.ClearFilters)
            advanceUntilIdle()
            assertEquals(CustomerFilter.ALL, model.uiState.value.filter)
            assertEquals("", model.uiState.value.query)
            assertEquals(listOf(1L), ids(model.uiState.value.content))
        }

    @Test
    fun `no customer at all is the empty state`() {
        assertEquals(
            CustomerListContent.Empty,
            CustomerListViewModel.contentOf(
                CustomersOverview(emptyList(), emptyList(), Money.ZERO),
                "x",
                CustomerFilter.ARCHIVED,
            ),
        )
    }

    @Test
    fun `a failed read shows the error and retrying reads again`() = runTest(dispatcher) {
        operations.failReads = true
        val model = CustomerListViewModel(operations)
        observe(model.uiState)
        advanceUntilIdle()
        assertEquals(CustomerListContent.ReadError, model.uiState.value.content)

        operations.failReads = false
        model.onAction(CustomerListAction.Retry)
        advanceUntilIdle()
        assertTrue(model.uiState.value.content is CustomerListContent.Customers)
    }

    @Test
    fun `reactivating says so, and a storage failure says why`() = runTest(dispatcher) {
        val model = CustomerListViewModel(operations)
        observe(model.uiState)

        model.onAction(CustomerListAction.Reactivate(5L))
        advanceUntilIdle()
        assertEquals(CustomerMessage.Reactivated, model.uiState.value.message)
        assertEquals(listOf(5L), operations.reactivated)

        model.onAction(CustomerListAction.MessageShown)
        operations.lifecycleResult = CustomerLifecycleResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(CustomerListAction.Reactivate(5L))
        advanceUntilIdle()
        assertEquals(CustomerMessage.Failed(outOfSpace = true), model.uiState.value.message)

        model.onAction(CustomerListAction.MessageShown)
        operations.lifecycleResult = CustomerLifecycleResult.Unchanged
        model.onAction(CustomerListAction.Reactivate(5L))
        advanceUntilIdle()
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `the sheet keeps what was written on errors and closes on save`() = runTest(dispatcher) {
        val model = CustomerListViewModel(operations)
        observe(model.uiState)
        model.onAction(CustomerListAction.NewCustomer())
        val editor = model.editor
        editor.onAction(CustomerEditorAction.Name("Eva"))
        editor.onAction(CustomerEditorAction.Phone("uno"))
        operations.saveResult = SaveCustomerResult.Invalid(setOf(CustomerFormIssue.PHONE_FORMAT))

        editor.onAction(CustomerEditorAction.Save)
        advanceUntilIdle()
        assertEquals(setOf(CustomerFormIssue.PHONE_FORMAT), editor.editor.value?.issues)
        assertEquals("uno", editor.editor.value?.phone)

        editor.onAction(CustomerEditorAction.Phone("300"))
        assertTrue(editor.editor.value?.issues.orEmpty().isEmpty())

        operations.saveResult = SaveCustomerResult.StorageFailed(StorageFailure.IO_ERROR)
        editor.onAction(CustomerEditorAction.Save)
        advanceUntilIdle()
        assertEquals(SaveFailure(outOfSpace = false), editor.editor.value?.saveFailed)
        assertEquals("Eva", editor.editor.value?.name)

        operations.saveResult = SaveCustomerResult.Saved(9L)
        editor.onAction(CustomerEditorAction.Save)
        advanceUntilIdle()
        assertNull(editor.editor.value)
        assertEquals(CustomerMessage.Saved, model.uiState.value.message)
        assertEquals(CustomerForm("Eva", phone = "300"), operations.saved.last().second)
    }

    @Test
    fun `a repeated name warns once and the next save creates it anyway`() = runTest(dispatcher) {
        val model = CustomerListViewModel(operations)
        observe(model.uiState)
        model.onAction(CustomerListAction.NewCustomer("Ana Ríos"))
        val editor = model.editor
        operations.saveResult = SaveCustomerResult.SameName

        editor.onAction(CustomerEditorAction.Save)
        advanceUntilIdle()
        assertTrue(editor.editor.value?.sameNameWarned == true)
        assertFalse(operations.allowed.last())

        operations.saveResult = SaveCustomerResult.Saved(10L)
        editor.onAction(CustomerEditorAction.Save)
        advanceUntilIdle()
        assertTrue(operations.allowed.last())
        assertNull(editor.editor.value)
    }

    @Test
    fun `changing the name after the warning asks again`() {
        val editor = CustomerEditorModel(TestScope(dispatcher), { _, _, _ -> error("") }, {})
        editor.openNew("Ana")
        editor.onAction(CustomerEditorAction.Name("Ana R"))
        assertFalse(editor.editor.value?.sameNameWarned == true)
    }

    @Test
    fun `a picked contact fills only the name and the phone it has`() {
        val editor = CustomerEditorModel(TestScope(dispatcher), { _, _, _ -> error("") }, {})
        editor.openNew()
        editor.onAction(CustomerEditorAction.Phone("555"))
        editor.onAction(CustomerEditorAction.PickContact)
        editor.contactsUnavailable()
        assertTrue(editor.editor.value?.noContactsApp == true)

        editor.fillFromContact("Eva Luna", null)
        val filled = requireNotNull(editor.editor.value)
        assertEquals("Eva Luna", filled.name)
        assertEquals("555", filled.phone)
        assertFalse(filled.noContactsApp)

        editor.fillFromContact(" ", "300 111")
        assertEquals("Eva Luna", editor.editor.value?.name)
        assertEquals("300 111", editor.editor.value?.phone)
    }

    @Test
    fun `editing keeps the price list, and an anonymized customer opens nothing`() {
        val editor = CustomerEditorModel(TestScope(dispatcher), { _, _, _ -> error("") }, {})
        editor.openEdit(CustomerSamples.list[0].copy(priceListId = 3L))
        assertEquals(3L, editor.editor.value?.form?.priceListId)
        assertEquals("300 000 000", editor.editor.value?.phone)

        editor.onAction(CustomerEditorAction.Dismiss)
        editor.openEdit(CustomerSamples.list[5])
        assertNull(editor.editor.value)
    }

    @Test
    fun `the badge says who owes whom and draws nothing at zero`() {
        assertEquals(BalanceBadgeVariant.OWES, BalanceBadgeVariant.forCustomer(Money(1)))
        assertEquals(
            BalanceBadgeVariant.OVERDUE,
            BalanceBadgeVariant.forCustomer(Money(1), overdue = true),
        )
        assertEquals(BalanceBadgeVariant.CREDIT, BalanceBadgeVariant.forCustomer(Money(-1), true))
        assertNull(BalanceBadgeVariant.forCustomer(Money.ZERO, overdue = true))
        assertEquals(BalanceBadgeVariant.SUPPLIER_OWED, BalanceBadgeVariant.forSupplier(Money(1)))
        assertEquals(
            BalanceBadgeVariant.OVERDUE,
            BalanceBadgeVariant.forSupplier(Money(1), overdue = true),
        )
        assertEquals(BalanceBadgeVariant.CREDIT, BalanceBadgeVariant.forSupplier(Money(-1)))
        assertNull(BalanceBadgeVariant.forSupplier(Money.ZERO))
    }

    private fun ids(content: CustomerListContent): List<Long> =
        (content as CustomerListContent.Customers).customers.map { it.id }

    private fun TestScope.observe(flow: Flow<*>) {
        backgroundScope.launch(dispatcher) { flow.collect {} }
    }
}

/** Las muestras como las daría el dominio: vigentes y archivados aparte. */
private fun sampleOverview(): CustomersOverview {
    val (archived, active) = CustomerSamples.list.partition(Customer::isArchived)
    return CustomersOverview(
        active,
        archived,
        receivable = Money(134_000),
    )
}

/** Operaciones con respuestas fijas: los clientes de las muestras, y lo que se pidió. */
private class FakeCustomerOperations : CustomerOperations {
    var failReads = false
    var saveResult: SaveCustomerResult = SaveCustomerResult.Saved(1L)
    var lifecycleResult: CustomerLifecycleResult = CustomerLifecycleResult.Done
    val saved = mutableListOf<Pair<Long?, CustomerForm>>()
    val allowed = mutableListOf<Boolean>()
    val reactivated = mutableListOf<Long>()
    val overview = MutableStateFlow(sampleOverview())
    val loyalty = MutableStateFlow(false)

    override fun customers(): Flow<CustomersOverview> = overview.map {
        check(!failReads) { "Lectura fallida" }
        it
    }

    override fun loyaltyEnabled(): Flow<Boolean> = loyalty

    override suspend fun save(
        editingId: Long?,
        form: CustomerForm,
        allowSameName: Boolean,
    ): SaveCustomerResult {
        saved += editingId to form
        allowed += allowSameName
        return saveResult
    }

    override suspend fun reactivate(customerId: Long): CustomerLifecycleResult {
        reactivated += customerId
        return lifecycleResult
    }
}
