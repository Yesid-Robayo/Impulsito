package io.github.yesidrobayo.shopledger.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseInterpreter
import java.io.File
import org.junit.Test

/**
 * Mide el intérprete de frases contra el corpus escrito a ciegas: lee el catálogo de prueba y las
 * frases de cada idioma, compara cada lectura con lo esperado y escribe un informe en
 * `build/phrase-report.md`.
 *
 * Es una medición, no una puerta: solo falla si no puede leer los archivos. Cada caso queda en una
 * clase:
 *
 * - `CORRECT`: todo igual a lo esperado.
 * - `ASKED`: todo igual, y lo esperado era preguntar (elegir entre productos, crear uno nuevo o
 *   avisar un problema).
 * - `SAFE`: lo esperado era una operación y el intérprete no registró nada: la app pide repetir.
 * - `PARTIAL`: la operación es la correcta, y algo de lo demás no.
 * - `WRONG`: la operación es otra, o una línea propone con confianza un producto que no es el
 *   esperado. Es el peor caso: registraría algo equivocado sin preguntar. Sale como "Peligroso".
 *
 * Los ids impares son la medición final: el informe solo da sus totales y detalla los pares.
 *
 * Lo que un caso no dice se compara con su valor vacío (`null`, `false` o lista vacía), porque el
 * contrato los devuelve todos. Con una sola línea, un total dicho en la línea o en toda la
 * operación es lo mismo para la pantalla, así que se comparan juntos. Un nombre nuevo se compara
 * sin tildes, mayúsculas, plurales ni tratamiento.
 */
class PhraseCorpusTest {
    @Test
    fun `the interpreter is measured against the blind corpus`() {
        measure(CORPUS_DIR, "build/phrase-report.md")
    }

    /**
     * La misma medición con la guía de conversación: frases de cada operación dichas de muchas
     * formas, con muletillas, dos oraciones y lo que nunca debe registrarse. Su informe va aparte.
     */
    @Test
    fun `the interpreter is measured against the conversation guide`() {
        measure(GUIDE_DIR, "build/phrase-guide-report.md")
    }

    private fun measure(dir: String, reportPath: String) {
        val fixture = Json.parse(File(FIXTURE).readText()).obj()
        val results = LANGUAGES.flatMap { (code, language) ->
            val catalog = FixtureCatalog(fixture, code)
            readCases(File("$dir/$code.json")).map { case ->
                evaluate(case, code, language, catalog)
            }
        }
        val report = File(reportPath)
        report.parentFile.mkdirs()
        report.writeText(Report(results).markdown())
        println(Report(results).summary())
    }

    private fun evaluate(
        case: CorpusCase,
        code: String,
        language: PhraseLanguage,
        catalog: FixtureCatalog,
    ): CaseResult {
        val reading = PhraseInterpreter.read(
            case.text,
            language,
            catalog.style,
            catalog.moneyDecimals,
            catalog.catalog,
        )
        val got = catalog.describe(reading)
        val expected = Expectation.of(case.expected)
        return CaseResult(code, case, expected, got, classify(expected, got))
    }

    private fun readCases(file: File): List<CorpusCase> {
        val root = Json.parse(file.readText())
        val items = root.arrOrNull() ?: CASE_LISTS.firstNotNullOf { root.obj()[it]?.arrOrNull() }
        return items.mapIndexed { index, item ->
            val obj = item.obj()
            val text = TEXT_KEYS.firstNotNullOf { obj[it]?.strOrNull() }
            val expected = EXPECTED_KEYS.firstNotNullOfOrNull { obj[it]?.objOrNull() } ?: obj
            val tags = obj["tags"]?.arrOrNull()?.mapNotNull { it.strOrNull() }
                ?: listOfNotNull(obj["tag"]?.strOrNull())
            val id = obj["id"]?.let { it.strOrNull() ?: it.render() } ?: (index + 1).toString()
            CorpusCase(id, text, tags.ifEmpty { listOf("untagged") }, expected)
        }
    }

    private companion object {
        const val FIXTURE = "src/test/resources/phrase/fixture.json"
        const val CORPUS_DIR = "src/test/resources/phrase/corpus"
        const val GUIDE_DIR = "src/test/resources/phrase/guide"
        val LANGUAGES = listOf(
            "es" to PhraseLanguage.ES,
            "en" to PhraseLanguage.EN,
            "pt" to PhraseLanguage.PT,
        )
        val CASE_LISTS = listOf("cases", "items", "phrases", "corpus")
        val TEXT_KEYS = listOf("text", "phrase", "input", "frase", "utterance")
        val EXPECTED_KEYS = listOf("expected", "expect", "output", "reading")
    }
}

private data class CorpusCase(
    val id: String,
    val text: String,
    val tags: List<String>,
    val expected: Map<String, Json>,
) {
    /**
     * Si el número del id es impar: esos casos son la medición final y no se muestran en detalle.
     */
    val isOdd: Boolean get() = (id.takeLastWhile(Char::isDigit).toIntOrNull() ?: 0) % 2 == 1
}

/**
 * El veredicto de un caso. WRONG sale en el informe como "Peligroso": registraría algo equivocado.
 */
private enum class Verdict(val label: String) {
    CORRECT("CORRECT"),
    ASKED("ASKED"),
    SAFE("SAFE"),
    PARTIAL("PARTIAL"),
    WRONG("Peligroso"),
}

private data class CaseResult(
    val language: String,
    val case: CorpusCase,
    val expected: Expectation,
    val got: Expectation,
    val verdict: Verdict,
)

private fun classify(expected: Expectation, got: Expectation): Verdict {
    // No registrar nada cuando sí había una operación es seguro: la app pide repetir la frase.
    if (expected.intent != null && got.intent == null) return Verdict.SAFE
    if (expected.intent != got.intent) return Verdict.WRONG
    val confidentlyWrong = expected.lines.zip(got.lines).any { (want, have) ->
        have.product is Ref.Key && have.product != want.product
    } || (got.target is Ref.Key && got.target != expected.target)
    if (confidentlyWrong) return Verdict.WRONG
    if (!same(expected.normalized(), got.normalized())) return Verdict.PARTIAL
    return if (expected.asks) Verdict.ASKED else Verdict.CORRECT
}

private class Report(private val results: List<CaseResult>) {
    private val even = results.filterNot { it.case.isOdd }
    private val odd = results.filter { it.case.isOdd }

    fun summary(): String = "Pares: ${counts(even)}. Impares: ${counts(odd)}."

    private fun counts(items: List<CaseResult>): String = "${items.size} frases, " +
        Verdict.entries.joinToString(", ") { verdict ->
            "${verdict.label} ${items.count { it.verdict == verdict }}"
        }

    fun markdown(): String = buildString {
        appendLine("# Medición del intérprete de frases")
        appendLine()
        appendLine(summary())
        appendLine()
        appendLine("## Totales por paridad del id")
        appendLine()
        appendLine(
            "Los ids pares sirven para ajustar; los impares, la medición final, solo se cuentan.",
        )
        appendLine()
        table(mapOf("pares" to even, "impares" to odd))
        appendLine("## Por idioma y paridad")
        appendLine()
        table(results.groupBy { "${it.language} ${if (it.case.isOdd) "impares" else "pares"}" })
        appendLine("## Pares por etiqueta")
        appendLine()
        table(
            even.flatMap { result ->
                result.case.tags.map { it to result }
            }.groupBy({ it.first }, { it.second }),
        )
        appendLine("## Pares que no salieron correctos")
        appendLine()
        for (result in even.filter {
            it.verdict != Verdict.CORRECT
        }.sortedByDescending { it.verdict.ordinal }) {
            appendLine("### ${result.verdict.label} · ${result.language} · ${result.case.id}")
            appendLine()
            appendLine("> ${result.case.text}")
            appendLine()
            appendLine("Etiquetas: ${result.case.tags.joinToString(", ")}")
            appendLine()
            appendLine("- Esperado: `${result.expected}`")
            appendLine("- Obtenido: `${result.got}`")
            appendLine()
        }
    }

    private fun StringBuilder.table(groups: Map<String, List<CaseResult>>) {
        val header = Verdict.entries.joinToString(" | ") { it.label }
        appendLine("| Grupo | Casos | $header | Bien (CORRECT + ASKED) | Sin registrar (SAFE) |")
        appendLine("|---|---:|" + Verdict.entries.joinToString("") { "---:|" } + "---:|---:|")
        for ((group, items) in groups.toSortedMap()) {
            val counts = Verdict.entries.map { verdict -> items.count { it.verdict == verdict } }
            val good = counts[Verdict.CORRECT.ordinal] + counts[Verdict.ASKED.ordinal]
            val safe = counts[Verdict.SAFE.ordinal]
            val row = counts.joinToString(" | ")
            appendLine(
                "| $group | ${items.size} | $row | ${percent(
                    good,
                    items.size,
                )} | ${percent(safe, items.size)} |",
            )
        }
        appendLine()
    }

    private fun percent(part: Int, whole: Int): String =
        if (whole == 0) "-" else "${part * 100 / whole} %"
}
