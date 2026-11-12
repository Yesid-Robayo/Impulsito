package io.github.yesidrobayo.shopledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La auditoría encuentra un tocable sin nombre, y en una pantalla sin fallas no encuentra nada. Un
 * tocable de 32 dp no es una falla: Compose extiende su área de toque hasta 48 dp cuando no choca con
 * otro, y la auditoría mide esa área. Las sondas son textos de prueba, no de la app.
 */
@RunWith(AndroidJUnit4::class)
class A11yAuditTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun itFindsANamelessTapAndAcceptsTheExtendedTouchArea() {
        compose.setContent {
            PreviewTheme {
                Column {
                    Box(Modifier.size(SMALL.dp).clickable {}) { Text(PROBE) }
                    Box(Modifier.size(BIG.dp).clickable {})
                }
            }
        }

        val found = A11yAudit(compose).problems()

        assertEquals(found.joinToString("\n"), 1, found.size)
        assertTrue(found.single().startsWith("tocable sin nombre"))
    }

    @Test
    fun aCleanScreenHasNoProblems() {
        compose.setContent {
            PreviewTheme { Box(Modifier.size(BIG.dp).clickable {}) { Text(PROBE) } }
        }

        assertEquals(emptyList<String>(), A11yAudit(compose).problems())
    }

    private companion object {
        const val PROBE = "Sonda"
        const val SMALL = 32
        const val BIG = 56
    }
}
