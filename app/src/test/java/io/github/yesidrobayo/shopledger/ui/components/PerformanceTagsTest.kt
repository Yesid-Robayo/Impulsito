package io.github.yesidrobayo.shopledger.ui.components

import io.github.yesidrobayo.shopledger.navigation.TopLevelDestination
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las marcas que buscan las mediciones de rendimiento son un contrato con otro módulo, que no ve
 * este código y repite los valores a mano. Si una cambia, esta prueba lo dice antes de que las
 * mediciones esperen en vano una pantalla que ya no encuentran.
 */
class PerformanceTagsTest {
    @Test
    fun tagsKeepTheValuesTheMeasurementsSearchFor() {
        assertEquals("home_ready", PerformanceTags.HOME_READY)
        assertEquals("sale_history_list", PerformanceTags.SALE_HISTORY_LIST)
        assertEquals("tab_records", PerformanceTags.tab(TopLevelDestination.RECORDS.name))
    }

    @Test
    fun everyTabHasItsOwnTag() {
        val tags = TopLevelDestination.entries.map { PerformanceTags.tab(it.name) }
        assertEquals(tags.size, tags.toSet().size)
    }
}
