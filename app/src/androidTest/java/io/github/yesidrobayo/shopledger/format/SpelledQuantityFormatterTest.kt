package io.github.yesidrobayo.shopledger.format

import android.content.res.Resources
import android.os.Build
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.localization.resourcesIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cantidades escritas con el plural de su unidad inicial, en cada idioma de la app y con el formato
 * regional que se pide, sea cual sea el idioma del teléfono. La categoría sale de los decimales
 * visibles: los casos son los de la tabla de plurales de la guía de redacción, escritos a mano.
 */
@RunWith(Parameterized::class)
class SpelledQuantityFormatterTest(device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    private val spanish = resourcesIn("es")
    private val english = resourcesIn("en")
    private val portuguese = resourcesIn("pt")

    @Test
    fun theCategoryFollowsTheVisibleDecimalsInSpanish() {
        val kilograms = { milli: Long -> spell(spanish, "es-AR", "KILOGRAM", milli, true) }
        assertEquals("0,250 kilogramos", kilograms(250))
        // Con tres decimales visibles, 1,000 sigue siendo uno en español.
        assertEquals("1,000 kilogramo", kilograms(1_000))
        assertEquals("1,500 kilogramos", kilograms(1_500))
        assertEquals("2,000 kilogramos", kilograms(2_000))
        assertEquals("1 unidad", spell(spanish, "es-AR", "UNIT", 1_000, false))
        assertEquals("3 cajas", spell(spanish, "es-AR", "BOX", 3_000, false))
    }

    @Test
    fun theCategoryFollowsTheVisibleDecimalsInEnglish() {
        val kilograms = { milli: Long -> spell(english, "en-US", "KILOGRAM", milli, true) }
        assertEquals("0.250 kilograms", kilograms(250))
        // En inglés, 1 es singular y 1.000 no: los decimales visibles cambian la categoría.
        assertEquals("1.000 kilograms", kilograms(1_000))
        assertEquals("1.500 kilograms", kilograms(1_500))
        assertEquals("1 unit", spell(english, "en-US", "UNIT", 1_000, false))
        assertEquals("3 boxes", spell(english, "en-US", "BOX", 3_000, false))
        assertEquals("3 dozen", spell(english, "en-US", "DOZEN", 3_000, false))
    }

    @Test
    fun theCategoryFollowsTheVisibleDecimalsInPortuguese() {
        val kilograms = { milli: Long -> spell(portuguese, "pt-BR", "KILOGRAM", milli, true) }
        assertEquals("1,000 quilograma", kilograms(1_000))
        assertEquals("2,000 quilogramas", kilograms(2_000))
        assertEquals("1 unidade", spell(portuguese, "pt-BR", "UNIT", 1_000, false))
        assertEquals("3 caixas", spell(portuguese, "pt-BR", "BOX", 3_000, false))
        // Entre 0 y 2 sin llegar a 2, el portugués usa el singular desde que ICU trae la regla
        // nueva. Antes, Android usaba el plural: las dos formas se entienden, y no se corrige.
        for ((milli, text) in listOf(250L to "0,250", 1_500L to "1,500")) {
            assertByVersion(
                kilograms(milli),
                old = "$text quilogramas",
                new = "$text quilograma",
                lastOld = LAST_PORTUGUESE_PLURAL_BELOW_TWO_SDK,
                firstNew = FIRST_PORTUGUESE_SINGULAR_BELOW_TWO_SDK,
            )
        }
    }

    @Test
    fun aMillionWholeUnitsTakesTheManyFormWhereTheLanguageHasIt() {
        val million = 1_000_000_000L
        assertEquals("1,000,000 units", spell(english, "en-US", "UNIT", million, false))
        for (resources in listOf(spanish, portuguese)) {
            assertByVersion(
                spell(resources, "es-AR", "UNIT", million, false),
                old = "1.000.000 unidades",
                new = "1.000.000 de unidades",
                lastOld = LAST_WITHOUT_MANY_FORM_SDK,
                firstNew = FIRST_WITH_MANY_FORM_SDK,
            )
        }
        // Con decimales visibles ya no es un millón exacto de unidades enteras.
        assertEquals("1.000.000,000 kilogramos", spell(spanish, "es-AR", "KILOGRAM", million, true))
    }

    @Test
    fun theWordsFollowTheLanguageAndTheNumberFollowsTheRegion() {
        // La app en inglés con el formato de una región de coma decimal: el número no cambia.
        assertEquals("1,500 kilograms", spell(english, "es-AR", "KILOGRAM", 1_500, true))
        assertEquals("1,000 kilograms", spell(english, "es-AR", "KILOGRAM", 1_000, true))
        assertEquals("1.500 kilogramos", spell(spanish, "en-US", "KILOGRAM", 1_500, true))
        assertEquals("1,234.500 kilograms", spell(english, "en-US", "KILOGRAM", 1_234_500, true))
        assertEquals("1.234,500 kilogramos", spell(spanish, "es-AR", "KILOGRAM", 1_234_500, true))
    }

    @Test
    fun negativesZeroAndFractionsOfACountingUnitAreShownAsTheyAre() {
        assertEquals("−2 unidades", spell(spanish, "es-AR", "UNIT", -2_000, false))
        assertEquals("−0,250 kilogramos", spell(spanish, "es-AR", "KILOGRAM", -250, true))
        assertEquals("0 units", spell(english, "en-US", "UNIT", 0, false))
        assertEquals("0.000 kilograms", spell(english, "en-US", "KILOGRAM", 0, true))
        assertEquals("0.001 kilograms", spell(english, "en-US", "KILOGRAM", 1, true))
        // Media caja no debería existir, pero redondearla escondería un dato.
        assertEquals("12,500 cajas", spell(spanish, "es-AR", "BOX", 12_500, false))
    }

    @Test
    fun theExtremesOfLongAreExact() {
        assertEquals(
            "9,223,372,036,854,775.807 kilograms",
            spell(english, "en-US", "KILOGRAM", Long.MAX_VALUE, true),
        )
        assertEquals(
            "−9,223,372,036,854,775.808 kilograms",
            spell(english, "en-US", "KILOGRAM", Long.MIN_VALUE, true),
        )
    }

    @Test
    fun aUnitWithoutAKnownPluralGivesNothing() {
        val formatter = SpelledQuantityFormatter(requireNotNull(FormatLocale.of("es-AR")))
        val three = QuantityMilli(3_000)
        // Una unidad de la persona, una inicial renombrada y una clave desconocida: `3 × caja`.
        assertNull(formatter.format(spanish, null, "Bandeja", three, allowsFraction = false))
        assertNull(formatter.format(spanish, "BOX", "Cajón", three, allowsFraction = false))
        assertNull(formatter.format(spanish, "FUTURE_KEY", null, three, allowsFraction = false))
        assertNull(formatter.format(spanish, null, null, three, allowsFraction = false))
    }

    @Test
    fun everyPatternOfEveryLanguageIsAPluralWithAFallback() {
        val formatter = SpelledQuantityFormatter(requireNotNull(FormatLocale.of("es-AR")))
        for (resources in listOf(spanish, english, portuguese)) {
            for (key in SeedTable.UNITS.systemKeys) {
                val pattern = resources.getString(requireNotNull(SeedText.unitQuantityRes(key)))
                assertTrue(key, "other {" in SpelledQuantityFormatter.pluralBody(pattern))
                val text = formatter.format(resources, key, null, QuantityMilli(7_000), false)
                assertTrue("$key $text", text != null && text.startsWith("7 ") && "#" !in text)
            }
        }
    }

    @Test
    fun aResourceThatIsNotAQuantityPluralIsRejected() {
        for (broken in listOf(
            "# kilogramos",
            "{n, plural, other {# kg}}",
            "{qty, select, other {#}}",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                SpelledQuantityFormatter.pluralBody(broken)
            }
        }
    }

    /**
     * Comprueba la forma que da el ICU de esta versión de Android: [old] hasta [lastOld], [new]
     * desde [firstNew], y cualquiera de las dos en las versiones de en medio, que no se midieron.
     */
    private fun assertByVersion(
        actual: String?,
        old: String,
        new: String,
        lastOld: Int,
        firstNew: Int,
    ) {
        when {
            Build.VERSION.SDK_INT <= lastOld -> assertEquals(old, actual)
            Build.VERSION.SDK_INT >= firstNew -> assertEquals(new, actual)
            else -> assertTrue("$actual", actual == old || actual == new)
        }
    }

    private fun spell(
        resources: Resources,
        region: String,
        key: String,
        milli: Long,
        allowsFraction: Boolean,
    ): String? = SpelledQuantityFormatter(requireNotNull(FormatLocale.of(region)))
        .format(resources, key, null, QuantityMilli(milli), allowsFraction)

    companion object {
        /** La última versión medida en que el portugués usa el plural entre 0 y 2: Android 8. */
        const val LAST_PORTUGUESE_PLURAL_BELOW_TWO_SDK = 26

        /** La primera versión medida en que usa el singular: Android 10. 8.1 y 9 no se midieron. */
        const val FIRST_PORTUGUESE_SINGULAR_BELOW_TWO_SDK = 29

        /** La última versión sin la forma de los millones exactos: Android 12L, con ICU 68. */
        const val LAST_WITHOUT_MANY_FORM_SDK = 32

        /** La primera con ella, en español y portugués: Android 13, con ICU 70. */
        const val FIRST_WITH_MANY_FORM_SDK = 33

        @JvmStatic
        @Parameterized.Parameters(name = "device {0}")
        fun devices(): List<ForeignDevice> = ForeignDevice.ALL
    }
}
