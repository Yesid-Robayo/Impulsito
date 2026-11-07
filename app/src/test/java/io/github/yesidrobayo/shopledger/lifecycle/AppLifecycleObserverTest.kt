package io.github.yesidrobayo.shopledger.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El respaldo sigue a la app entera: al abrirse encola lo que decidió el estado del respaldo, sin
 * cambiarlo, y al pasar a segundo plano programa la copia. Cada evento corre aparte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLifecycleObserverTest {
    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = error(
                "El observador no mira el ciclo de vida",
            )
        }

    @Test
    fun openingTheAppQueuesWhatTheBackupStatusDecided() = runTest(UnconfinedTestDispatcher()) {
        for (decision in AppOpenUpload.entries) {
            val seen = mutableListOf<String>()
            val observer =
                AppLifecycleObserver(
                    scope = this,
                    appOpenUpload = { decision },
                    onAppOpened = { seen += "abre $it" },
                    onAppBackgrounded = { seen += "segundo plano" },
                )

            observer.onStart(owner)

            assertEquals(listOf("abre $decision"), seen)
        }
    }

    @Test
    fun goingToTheBackgroundSchedulesTheCopy() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<String>()
        val observer =
            AppLifecycleObserver(
                scope = this,
                appOpenUpload = { error("Al pasar a segundo plano no se pregunta el estado") },
                onAppOpened = { seen += "abre $it" },
                onAppBackgrounded = { seen += "segundo plano" },
            )

        observer.onStop(owner)

        assertEquals(listOf("segundo plano"), seen)
    }
}
