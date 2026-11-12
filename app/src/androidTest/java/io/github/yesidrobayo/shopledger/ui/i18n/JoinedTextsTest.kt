package io.github.yesidrobayo.shopledger.ui.i18n

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.localization.resourcesIn
import io.github.yesidrobayo.shopledger.ui.product.BulkMessage
import io.github.yesidrobayo.shopledger.ui.product.bulkMessageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dos textos que se muestran juntos se unen con un recurso de cada idioma, nunca con la puntuación
 * escrita en el código: el orden y los signos entre los dos también se traducen.
 */
@RunWith(AndroidJUnit4::class)
class JoinedTextsTest {
    @Test
    fun undoingPricesWithSomeKeptJoinsBothSentencesWithTheLanguageResource() {
        for (tag in listOf("en", "es", "pt")) {
            val resources = resourcesIn(tag)
            val undone = resources.getQuantityString(R.plurals.confirm_prices_undone, 3, 3)
            val partial = resources.getQuantityString(R.plurals.notice_undo_partial, 1, 1)

            val text = bulkMessageText(resources, BulkMessage.Undone(reverted = 3, kept = 1))

            assertEquals(resources.getString(R.string.bulk_undo_mixed, undone, partial), text)
            assertTrue(tag, text.contains(undone) && text.contains(partial))
        }
    }

    @Test
    fun labelAndValueAndSupportLinesKeepBothPartsInEveryLanguage() {
        for (tag in listOf("en", "es", "pt")) {
            val resources = resourcesIn(tag)

            val spoken = resources.getString(R.string.a11y_label_value, "A", "B")
            val line = resources.getString(R.string.support_line, "A", "B")

            assertTrue(tag, spoken.indexOf("A") in 0 until spoken.indexOf("B"))
            assertTrue(tag, line.indexOf("A") in 0 until line.indexOf("B"))
        }
    }
}
