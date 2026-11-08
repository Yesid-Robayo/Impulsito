package io.github.yesidrobayo.shopledger.ui.experience

import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El centro `Negocio` sin Android: lo que está en uso va arriba y no se repite, un negocio nuevo
 * abre `Dinero`, los grupos se pliegan, la búsqueda ignora tildes y grupos, y sin base todo queda a
 * un toque. Los nombres son inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessHubViewModelTest {
    private val usage = MutableStateFlow<Set<BusinessTool>>(emptySet())

    private val customers =
        HubTool(BusinessTool.CUSTOMERS, "Clientes", "Ventas a crédito", AppNavKey.Customers())
    private val cash =
        HubTool(BusinessTool.CASH_REGISTER, "Caja", "Contar el efectivo", AppNavKey.CashRegister)
    private val units = HubTool(BusinessTool.UNITS, "Unidades", "Comprar por caja", AppNavKey.Units)
    private val tools = listOf(customers, cash, units)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun `a new business has nothing in use and opens the money group`() {
        val state = BusinessHubViewModel(usage).uiState.value

        val sections = hubSections(state, tools) as HubSections.Browse
        assertTrue(sections.inUse.isEmpty())
        assertEquals(
            listOf(
                HubGroup(ToolGroup.MONEY, listOf(customers, cash), expanded = true),
                HubGroup(ToolGroup.CATALOG_AND_INVENTORY, listOf(units), expanded = false),
            ),
            sections.groups,
        )
    }

    @Test
    fun `a tool in use goes on top and is not repeated in its group`() {
        val model = BusinessHubViewModel(usage)
        usage.value = setOf(BusinessTool.UNITS, BusinessTool.CUSTOMERS)

        val sections = hubSections(model.uiState.value, tools) as HubSections.Browse

        assertEquals(listOf(customers, units), sections.inUse)
        // Con algo en uso, ningún grupo empieza abierto.
        assertEquals(
            listOf(HubGroup(ToolGroup.MONEY, listOf(cash), expanded = false)),
            sections.groups,
        )
    }

    @Test
    fun `a tool that loses its data goes back to its group`() {
        val model = BusinessHubViewModel(usage)
        usage.value = setOf(BusinessTool.UNITS)
        usage.value = emptySet()

        val sections = hubSections(model.uiState.value, tools) as HubSections.Browse
        assertTrue(sections.inUse.isEmpty())
        assertEquals(listOf(units), sections.groups.last().tools)
    }

    @Test
    fun `groups fold and unfold, and the choice is kept`() {
        val model = BusinessHubViewModel(usage)

        model.onAction(BusinessHubAction.ToggleGroup(ToolGroup.MONEY))
        model.onAction(BusinessHubAction.ToggleGroup(ToolGroup.CATALOG_AND_INVENTORY))
        usage.value = setOf(BusinessTool.CUSTOMERS)

        val groups = (hubSections(model.uiState.value, tools) as HubSections.Browse).groups
        assertEquals(listOf(false, true), groups.map { it.expanded })
    }

    @Test
    fun `searching ignores accents, case and groups, and finds tools in use too`() {
        val model = BusinessHubViewModel(usage)
        usage.value = setOf(BusinessTool.CUSTOMERS)

        model.onAction(BusinessHubAction.Search("CRÉDITO"))
        assertEquals(HubSections.Search(listOf(customers)), hubSections(model.uiState.value, tools))

        model.onAction(BusinessHubAction.Search("nada así"))
        assertEquals(HubSections.Search(emptyList()), hubSections(model.uiState.value, tools))

        model.onAction(BusinessHubAction.Search("  "))
        assertTrue(hubSections(model.uiState.value, tools) is HubSections.Browse)
    }

    @Test
    fun `while reading, nothing is in use yet`() {
        val model = BusinessHubViewModel(MutableStateFlow(setOf(BusinessTool.UNITS)))
        assertEquals(setOf(BusinessTool.UNITS), model.uiState.value.inUse)

        val pending = BusinessHubViewModel(emptyFlow())
        assertEquals(null, pending.uiState.value.inUse)
        assertEquals(setOf(ToolGroup.MONEY), pending.uiState.value.openGroups())
    }

    @Test
    fun `without a database every tool stays one tap away`() {
        val state = BusinessHubViewModel(null).uiState.value

        assertEquals(emptySet<BusinessTool>(), state.inUse)
        val sections = hubSections(state, tools) as HubSections.Browse
        assertEquals(tools, sections.groups.flatMap { it.tools })
    }
}
