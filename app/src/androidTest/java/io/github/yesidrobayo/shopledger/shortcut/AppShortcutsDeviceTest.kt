package io.github.yesidrobayo.shopledger.shortcut

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: los atajos se publican en el idioma que recibe, no en el del teléfono, en su
 * orden, y cada uno abre la actividad principal con su pedido de la lista cerrada.
 */
@RunWith(AndroidJUnit4::class)
class AppShortcutsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun removeShortcuts() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    @Test
    fun theShortcutsFollowTheChosenLanguageInTheirOrder() = runBlocking {
        AppShortcuts(context, NoLog).refresh(textsIn("es"))

        val published = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }

        assertEquals(
            listOf(
                AppShortcuts.ID_SALE,
                AppShortcuts.ID_PHRASE,
                AppShortcuts.ID_EXPENSE,
                AppShortcuts.ID_PURCHASE,
            ),
            published.map { it.id },
        )
        assertEquals(
            listOf("Vender", "Decir", "Gasto", "Compra"),
            published.map { it.shortLabel.toString() },
        )
        assertEquals("Nueva venta", published.first().longLabel.toString())
    }

    @Test
    fun aNewLanguageRewritesTheShortcuts() = runBlocking {
        val shortcuts = AppShortcuts(context, NoLog)
        shortcuts.refresh(textsIn("es"))

        shortcuts.refresh(textsIn("pt"))

        val expense =
            ShortcutManagerCompat
                .getDynamicShortcuts(context)
                .single { it.id == AppShortcuts.ID_EXPENSE }
        assertEquals("Despesa", expense.shortLabel.toString())
    }

    @Test
    fun eachShortcutOpensTheMainActivityWithItsRequest() = runBlocking {
        AppShortcuts(context, NoLog).refresh(textsIn("en"))

        val requests =
            ShortcutManagerCompat
                .getDynamicShortcuts(context)
                .sortedBy { it.rank }
                .map { shortcut ->
                    assertEquals(
                        MainActivity::class.java.name,
                        shortcut.intent.component?.className,
                    )
                    AppOpenRequest.readFrom(shortcut.intent)
                }

        assertEquals(
            listOf(
                AppOpenRequest.NewSale,
                AppOpenRequest.Phrase,
                AppOpenRequest.NewExpense,
                AppOpenRequest.NewPurchase,
            ),
            requests,
        )
    }

    private object NoLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }

    private fun textsIn(tag: String): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration)
    }
}
