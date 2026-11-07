package io.github.yesidrobayo.shopledger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La segunda fase del reemplazo con la muerte del proceso tras cada operación, también dos veces
 * seguidas: siempre queda la base vieja o la nueva, completas, con su `-wal`, nunca una base vacía,
 * y la clave, la copia de seguridad y el origen de un deshacer quedan donde corresponde.
 *
 * Una muerte se prueba en todos los escenarios. Dos muertes seguidas, en `check`, en los escenarios
 * con más archivos en juego: con base vieja, `-wal`, clave pendiente y deshacer, con cada
 * verificación; sin base vieja; y sin copia de seguridad. Con `-PswapFull=true`, en todos.
 */
class StartupRestoreDeathTest {
    @get:Rule val folder = TemporaryFolder()

    private val harness by lazy { SwapHarness(folder.newFolder("swap")) }

    @Test
    fun `without any death every scenario ends where it should`() {
        val scenarios = SwapScenario.all()
        assertEquals(192, scenarios.size)
        for (scenario in scenarios) {
            assertEquals(
                "$scenario",
                emptyList<String>(),
                harness.run(scenario, emptyList()).violations,
            )
        }
    }

    @Test
    fun `a death after each operation never loses a database`() {
        var cases = 0
        for (scenario in SwapScenario.all()) {
            val operations = harness.run(scenario, emptyList()).operationsPerBoot.first()
            for (death in 1..operations) {
                assertEquals(
                    "$scenario, muerte $death",
                    emptyList<String>(),
                    harness.run(scenario, listOf(death)).violations,
                )
                cases++
            }
        }
        assertTrue("Se probaron $cases casos", cases > 2_000)
    }

    @Test
    fun `two deaths in a row never lose a database`() {
        val full = System.getProperty(FULL_PROPERTY) == "true"
        val scenarios = SwapScenario.all().filter { full || it.changesTheRecovery() }
        var cases = 0
        for (scenario in scenarios) {
            val operations = harness.run(scenario, emptyList()).operationsPerBoot.first()
            for (death in 1..operations) {
                val recovery = harness.run(scenario, listOf(death)).operationsPerBoot.getOrElse(1) {
                    0
                }
                for (second in 1..recovery) {
                    assertEquals(
                        "$scenario, muertes $death y $second",
                        emptyList<String>(),
                        harness.run(scenario, listOf(death, second)).violations,
                    )
                    cases++
                }
            }
        }
        assertTrue("Se probaron $cases casos", cases > 500)
    }

    private companion object {
        const val FULL_PROPERTY = "swap.full"
    }

    /**
     * Los escenarios con más archivos en juego, uno por camino de la recuperación: cada verificación
     * con base vieja, un reemplazo sin base vieja que termina y otro que se revierte, y uno que
     * termina sin copia de seguridad.
     */
    private fun SwapScenario.changesTheRecovery(): Boolean {
        val busiest = candidatePresent && encrypted && undo
        return busiest && when {
            hadActive && oldWal && safetyOk -> true

            !hadActive && safetyOk ->
                verify == VerifyBehavior.OK ||
                    verify == VerifyBehavior.COUNTERS_BAD

            hadActive && oldWal -> verify == VerifyBehavior.OK

            else -> false
        }
    }
}
