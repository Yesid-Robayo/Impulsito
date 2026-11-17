package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import org.junit.Assert.assertEquals
import org.junit.Test

/** Las piezas del intérprete por separado: palabras, números y el singular. */
class PhrasePiecesTest {
    private val spanish = PhraseLexicons.of(PhraseLanguage.ES)
    private val english = PhraseLexicons.of(PhraseLanguage.EN)
    private val portuguese = PhraseLexicons.of(PhraseLanguage.PT)

    private fun words(text: String) = PhraseTokenizer.tokenize(text).map { it.norm }

    private fun spanishNumber(text: String): Long? =
        NumberReader(spanish, COMMA_DECIMAL).read(PhraseTokenizer.tokenize(text), 0)?.milli

    private fun englishNumber(text: String): Long? =
        NumberReader(english, POINT_DECIMAL).read(PhraseTokenizer.tokenize(text), 0)?.milli

    @Test
    fun `the tokenizer keeps numbers whole and splits the rest`() {
        assertEquals(listOf("1,5", "2.500", ",", "pan"), words("1,5 2.500, pan"))
        assertEquals(listOf("2", "kg", "400", "ml", "x", "3"), words("2kg 400ml x3"))
        assertEquals(listOf("1/4", "c/u"), words("1/4 c/u"))
        assertEquals(listOf("sr", "ramiro", "panes", ","), words("Sr. Ramiro panes."))
        assertEquals(listOf(",", "5", "a", ",", "5", "5", ",", "a"), words(",5 a,5 5,a"))
        assertEquals(listOf("im", "t", "shirt", "20"), words("I'm t-shirt $20"))
        assertEquals(listOf(",", "pan", "a", "1", ","), words(". pan a1."))
        assertEquals(listOf("hola", "que", "tal", ","), words("¿hola (qué) tal?"))
    }

    @Test
    fun `a separator is a token that cuts lines`() {
        assertEquals(listOf(false, true), PhraseTokenizer.tokenize("pan,").map { it.isSeparator })
    }

    @Test
    fun `numbers in figures follow the style of the keyboard`() {
        assertEquals(1_500L, spanishNumber("1,5"))
        assertEquals(1_500_000L, spanishNumber("1.500"))
        assertEquals(1_500L, spanishNumber("1.5"))
        assertEquals(2_500_000_000L, spanishNumber("2.500.000"))
        assertEquals(2_500_500L, spanishNumber("2.500,5"))
        assertEquals(1_500L, englishNumber("1.5"))
        assertEquals(1_500_000L, englishNumber("1,500"))
        assertEquals(250L, spanishNumber("1/4"))
        assertEquals(1_234_000L, spanishNumber("1234"))
    }

    @Test
    fun `figures that cannot be read are not a number`() {
        assertEquals(null, spanishNumber("1.2.3"))
        assertEquals(null, spanishNumber("1234.567"))
        assertEquals(null, spanishNumber("1.50.000"))
        assertEquals(null, spanishNumber("1,5.000"))
        assertEquals(null, spanishNumber("1/0"))
        assertEquals(null, spanishNumber("1/2/3"))
        assertEquals(null, spanishNumber("1234567890123"))
        assertEquals(null, spanishNumber("1234567890123/4"))
        assertEquals(null, parseDigits("1/", COMMA_DECIMAL))
        assertEquals(null, parseDigits("pan", COMMA_DECIMAL))
        assertEquals(null, parseDigits("1/a", COMMA_DECIMAL))
    }

    @Test
    fun `numbers in words`() {
        assertEquals(35_000L, spanishNumber("treinta y cinco"))
        assertEquals(2_500_000L, spanishNumber("dos mil quinientos"))
        assertEquals(2_500_000L, spanishNumber("dos mil y quinientos"))
        assertEquals(2_500L, spanishNumber("dos y medio"))
        assertEquals(250L, spanishNumber("un cuarto"))
        assertEquals(750L, spanishNumber("tres cuartos"))
        assertEquals(500L, spanishNumber("medio"))
        assertEquals(1_000_000L, spanishNumber("mil"))
        assertEquals(1_500_000L, spanishNumber("mil quinientos"))
        assertEquals(20_000_000L, spanishNumber("20 mil"))
        assertEquals(2_000L, spanishNumber("un par de"))
        assertEquals(250_000L, englishNumber("two hundred fifty"))
        assertEquals(100_000L, englishNumber("a hundred"))
        assertEquals(2_500L, englishNumber("two and a half"))
    }

    @Test
    fun `a conjunction only joins what is said together`() {
        assertEquals(1_000_000L, spanishNumber("mil y dos"))
        assertEquals(2_000L, spanishNumber("dos y tres"))
        assertEquals(30_000L, spanishNumber("treinta y"))
        assertEquals(30_000L, spanishNumber("treinta y pan"))
        assertEquals(2_000L, spanishNumber("dos tres"))
    }

    @Test
    fun `a number followed by a currency or said in money slang is money`() {
        val reader = NumberReader(spanish, COMMA_DECIMAL)
        assertEquals(
            NumberRead(2_500_000L, 4, money = true),
            reader.read(PhraseTokenizer.tokenize("dos mil quinientos pesos"), 0),
        )
        assertEquals(
            NumberRead(20_000_000L, 2, money = true),
            reader.read(PhraseTokenizer.tokenize("20 lucas"), 0),
        )
        assertEquals(
            NumberRead(3_000_000L, 3, money = true),
            reader.read(PhraseTokenizer.tokenize("2 lucas mil"), 0),
        )
        assertEquals(
            NumberRead(2_000_000L, 2, money = false),
            reader.read(PhraseTokenizer.tokenize("2 mil"), 0),
        )
        assertEquals(null, reader.read(PhraseTokenizer.tokenize("pan"), 0))
    }

    @Test
    fun `numbers too large to fit are not read`() {
        assertEquals(null, spanishNumber("999999999999 millones"))
        assertEquals(null, englishNumber("999999999999 hundred"))
        assertEquals(999_999_999_999_000L, spanishNumber("999999999999"))
    }

    @Test
    fun `edit distance with transpositions`() {
        assertEquals(0, PhraseWords.distance("pan", "pan"))
        assertEquals(1, PhraseWords.distance("harina", "hariana"))
        assertEquals(1, PhraseWords.distance("ab", "ba"))
        assertEquals(1, PhraseWords.distance("caja", "cjaa"))
        assertEquals(3, PhraseWords.distance("", "abc"))
        assertEquals(3, PhraseWords.distance("abc", ""))
    }

    @Test
    fun `closeness tolerates more errors in longer words and none in canonical ones`() {
        assertEquals(null, PhraseWords.closeness("pan", "pon"))
        assertEquals(null, PhraseWords.closeness("vela", "velo"))
        assertEquals(1, PhraseWords.closeness("harina", "harino"))
        assertEquals(1, PhraseWords.closeness("refri", "refrigerante"))
        assertEquals(null, PhraseWords.closeness("ref", "refrigerante"))
        assertEquals(1, PhraseWords.closeness("refri", "refrix"))
        assertEquals(null, PhraseWords.closeness("refra", "refrigerante"))
        assertEquals(null, PhraseWords.closeness("#n12", "#n12345"))
        assertEquals(2, PhraseWords.closeness("mozzarella", "mozarela"))
        assertEquals(null, PhraseWords.closeness("#n400000", "#n40000"))
        assertEquals(null, PhraseWords.closeness("abcd", "#n4000"))
    }

    @Test
    fun `cover pairs each word once`() {
        assertEquals(
            Cover(1, 0, listOf("de", "trigo")),
            PhraseWords.cover(listOf("harina"), listOf("harina", "de", "trigo")),
        )
        assertEquals(
            Cover(1, 0, emptyList()),
            PhraseWords.cover(listOf("pan", "pan"), listOf("pan")),
        )
    }

    @Test
    fun `singular rules of each language`() {
        assertEquals("pan", spanish.singular("panes"))
        assertEquals("lapiz", spanish.singular("lapices"))
        assertEquals("tomate", spanish.singular("tomates"))
        assertEquals("gas", spanish.singular("gas"))
        assertEquals("cookie", english.singular("cookies"))
        assertEquals("berrie", english.singular("berry"))
        assertEquals("box", english.singular("boxes"))
        assertEquals("strawberry", english.displaySingular("strawberries"))
        assertEquals("pao", portuguese.singular("paes"))
        assertEquals("limão", portuguese.singular("limões"))
        assertEquals("pastel", portuguese.singular("pasteis"))
        assertEquals("flor", portuguese.singular("flores"))
        assertEquals("bombom", portuguese.singular("bombons"))
    }

    @Test
    fun `a phrase table finds the longest phrase and close single words`() {
        val table = PhraseTable(listOf("me compraron" to 1, "compraron" to 2, "vendi" to 3))
        val tokens = PhraseTokenizer.tokenize("me compraron pan bendi")
        assertEquals(PhraseHit(0, 2, 1), table.matchAt(tokens, 0))
        assertEquals(PhraseHit(1, 2, 2), table.matchAt(tokens, 1))
        assertEquals(null, table.matchAt(tokens, 2))
        assertEquals(listOf(PhraseHit(0, 2, 1)), table.findAll(tokens))
        assertEquals(PhraseHit(3, 4, 3), table.closeAt(tokens, 3))
        assertEquals(null, table.closeAt(tokens, 2))
    }

    @Test
    fun `capitalizing an empty name leaves it empty`() {
        assertEquals("", "".capitalized())
        assertEquals("Pan", "pan".capitalized())
    }

    @Test
    fun `cutting and removing words`() {
        val tokens = PhraseTokenizer.tokenize("a b c d")
        assertEquals(listOf("a", "d"), tokens.cut(1, 3).map { it.norm })
        assertEquals(
            listOf("a", "d"),
            tokens.without(listOf(PhraseHit(1, 3, Unit))).map {
                it.norm
            },
        )
    }
}
