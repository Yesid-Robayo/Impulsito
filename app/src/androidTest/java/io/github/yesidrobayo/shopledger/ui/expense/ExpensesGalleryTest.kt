package io.github.yesidrobayo.shopledger.ui.expense

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.money.SAMPLE_ACCOUNTS
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de los gastos para la puerta de calidad visual: la lista con un gasto anulado, el mes
 * vacío, el gasto nuevo con un error, el gasto nuevo con su cuenta de dinero y la hoja de anular,
 * en claro y en oscuro. La letra es la del sistema. Solo corre con `captureDir`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ExpensesGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsTheExpensesInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val list = previewExpenses()
        val voidable = (list.content as ExpensesContent.Days).days.last().rows.single()
        val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
            "list" to { ExpensesScreen(list, onAction = {}) },
            "empty" to {
                ExpensesScreen(
                    list.copy(content = ExpensesContent.Empty, total = Money.ZERO),
                    onAction = {},
                )
            },
            "new" to {
                ExpensesScreen(
                    list.copy(
                        form = ExpenseForm(
                            amountText = "0",
                            categoryId = 9,
                            description = "Bolsas de papel",
                            errors = setOf(ExpenseFormError.AMOUNT_ZERO),
                        ),
                    ),
                    onAction = {},
                )
            },
            // Con cuentas en uso, la fila `Cuenta de dinero` con dos cuentas y una elegida.
            "new-account" to {
                ExpensesScreen(
                    list.copy(
                        accounts = SAMPLE_ACCOUNTS,
                        form = ExpenseForm(
                            amountText = "12000",
                            categoryId = 9,
                            description = "Bolsas de papel",
                            accountId = SAMPLE_ACCOUNTS[1].id,
                        ),
                    ),
                    onAction = {},
                )
            },
            "detail" to {
                ExpensesScreen(list.copy(detail = ExpenseDetailSheet(voidable)), onAction = {})
            },
            "void" to {
                ExpensesScreen(
                    list.copy(
                        detail = ExpenseDetailSheet(
                            voidable,
                            voiding = ExpenseVoidDraft(note = PreviewSamples.SHORT_NAME),
                        ),
                    ),
                    onAction = {},
                )
            },
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, content) in scenes) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        activity.setContent {
                            AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                    LocalDiscreetMode provides false,
                                ) {
                                    key(name, dark) { content() }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    // Sin el teclado, que taparía la cuenta de dinero bajo las categorías.
                    scenario.onActivity { activity ->
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .hide(WindowInsetsCompat.Type.ime())
                    }
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "$mode-expenses-$name.png"))
                }
            }
        }
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
