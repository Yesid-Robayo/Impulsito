package io.github.yesidrobayo.shopledger.domain.usecase.experience

import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.domain.port.ModuleUsageSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las herramientas en uso: cada cambio llega una vez, y una base que no se lee deja todas como no
 * usadas, sin romper la pantalla.
 */
class ObserveModuleUsageUseCaseTest {
    @Test
    fun `each change arrives once`() = runTest {
        val units = setOf(BusinessTool.UNITS)
        val source = ModuleUsageSource { flowOf(emptySet(), units, units, emptySet()) }

        assertEquals(
            listOf(emptySet(), units, emptySet()),
            ObserveModuleUsageUseCase(source)().toList(),
        )
    }

    @Test
    fun `a database that cannot be read leaves every tool available`() = runTest {
        val source =
            ModuleUsageSource {
                flow {
                    emit(setOf(BusinessTool.CUSTOMERS))
                    throw StorageFailureException(StorageFailure.IO_ERROR)
                }
            }

        assertEquals(
            listOf(setOf(BusinessTool.CUSTOMERS), emptySet()),
            ObserveModuleUsageUseCase(source)().toList(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `a programming error is not hidden`() = runTest {
        val source = ModuleUsageSource { flow { error("Error de programación") } }
        ObserveModuleUsageUseCase(source)().toList()
    }

    @Test
    fun `every group has tools and the order is the screen order`() {
        assertEquals(ToolGroup.entries, BusinessTool.entries.map { it.group }.distinct())
        assertEquals(27, BusinessTool.entries.size)
    }
}
