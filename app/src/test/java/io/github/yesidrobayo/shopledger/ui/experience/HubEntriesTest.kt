package io.github.yesidrobayo.shopledger.ui.experience

import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El cierre del centro `Negocio`: cada herramienta de la app tiene una sola entrada, con sus propios
 * textos y su propia pantalla, y el centro las muestra en el orden de la tabla de la pantalla,
 * aunque las entradas se hayan agregado en otro. Una herramienta nueva sin entrada, o una repetida,
 * rompe esta prueba.
 */
class HubEntriesTest {
    @Test
    fun everyToolHasExactlyOneEntry() {
        val tools = HUB_ENTRIES.map { it.tool }

        assertEquals(BusinessTool.entries.size, tools.size)
        assertEquals(BusinessTool.entries.toSet(), tools.toSet())
    }

    @Test
    fun noTwoEntriesShareATextOrAScreen() {
        val texts = HUB_ENTRIES.flatMap { listOf(it.name, it.description) }

        assertEquals(texts.size, texts.toSet().size)
        assertEquals(HUB_ENTRIES.size, HUB_ENTRIES.map { it.destination }.toSet().size)
    }

    @Test
    fun everyGroupListsItsToolsInTheOrderOfTheScreenTable() {
        val tools = HUB_ENTRIES.map { HubTool(it.tool, it.tool.name, "", it.destination) }
        val state = BusinessHubUiState(inUse = emptySet(), expanded = ToolGroup.entries.toSet())

        val sections = hubSections(state, tools.reversed()) as HubSections.Browse

        assertEquals(ToolGroup.entries.toList(), sections.groups.map { it.group })
        assertEquals(
            BusinessTool.entries.toList(),
            sections.groups.flatMap { group -> group.tools.map { it.tool } },
        )
    }

    @Test
    fun theToolsInUseFollowTheOrderOfTheScreenTable() {
        val tools = HUB_ENTRIES.map { HubTool(it.tool, it.tool.name, "", it.destination) }
        val inUse = setOf(BusinessTool.PRICE_INDEX, BusinessTool.CUSTOMERS, BusinessTool.ORDERS)

        val sections =
            hubSections(BusinessHubUiState(inUse = inUse), tools.reversed()) as HubSections.Browse

        assertEquals(
            listOf(BusinessTool.CUSTOMERS, BusinessTool.ORDERS, BusinessTool.PRICE_INDEX),
            sections.inUse.map { it.tool },
        )
    }
}
