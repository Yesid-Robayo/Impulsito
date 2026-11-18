package io.github.yesidrobayo.shopledger.phrase

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.phrase.CardFollowUp
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseInterpreter
import io.github.yesidrobayo.shopledger.domain.rule.phrase.correctedBy
import io.github.yesidrobayo.shopledger.domain.rule.phrase.followedBy
import io.github.yesidrobayo.shopledger.domain.rule.phrase.toDraft
import java.io.File
import org.junit.Test

/**
 * Mide las correcciones con la tarjeta abierta contra las de la guía de conversación: arma la
 * tarjeta de cada caso desde lo esperado de la primera frase, le aplica la corrección como lo hace
 * la pantalla y compara la tarjeta que queda. Escribe un informe en
 * `build/phrase-corrections-report.md`.
 *
 * La tarjeta inicial sale de `initial_expect`, no de leer `initial`: así una corrección se mide
 * aunque el intérprete lea mal la primera frase. Lo esperado pasa por la misma conversión a
 * tarjeta, y `extra` (el uso o la categoría del producto nuevo) se pone en el producto nuevo de la
 * tarjeta esperada, que ahora los lleva.
 *
 * Un caso con `screen` se mide como lo hace la pantalla entera: si la frase no corrige la tarjeta, se
 * lee con el intérprete y se suma a ella, o la deja como estaba. Ahí `applies: false` espera que la
 * tarjeta quede como estaba, y `expect`, cuando se aplica, es la tarjeta que queda.
 *
 * Es una medición, no una puerta: solo falla si no puede leer los archivos. Cada caso queda en una
 * clase:
 *
 * - `Bien`: se aplicó y quedó lo esperado, o no se aplicó y se esperaba leerla como frase nueva.
 * - `Sin aplicar`: debía aplicarse y no se aplicó; la pantalla la lee como frase nueva, y una
 *   frase corta casi nunca reemplaza la tarjeta.
 * - `Mal aplicada`: cambió la tarjeta, y no como se esperaba, o la cambió cuando debía leerse como
 *   frase nueva. Es el peor caso: la tarjeta queda con algo que no se dijo.
 *
 * Los ids impares son la medición final: el informe solo da sus totales y detalla los pares.
 */
class PhraseCorrectionCorpusTest {
    @Test
    fun `corrections are measured against the conversation guide`() {
        val fixture = Json.parse(File(FIXTURE).readText()).obj()
        val results = LANGUAGES.flatMap { (code, language) ->
            val catalog = FixtureCatalog(fixture, code)
            Json.parse(File("$GUIDE_DIR/corrections_$code.json").readText()).arr().map {
                evaluate(Correction.of(it.obj()), code, language, catalog)
            }
        }
        val report = File(REPORT)
        report.parentFile.mkdirs()
        report.writeText(CorrectionReport(results).markdown())
        println(CorrectionReport(results).summary())
    }

    private fun evaluate(
        case: Correction,
        code: String,
        language: PhraseLanguage,
        catalog: FixtureCatalog,
    ): CorrectionResult {
        val initial = requireNotNull(catalog.reading(case.initial).toDraft()) {
            "La tarjeta inicial de ${case.id} no tiene operación"
        }
        val corrected = initial.correctedBy(
            case.correction,
            language,
            catalog.style,
            catalog.moneyDecimals,
            catalog.catalog,
        )
        val got = corrected ?: followed(initial, case, language, catalog).takeIf { case.screen }
        val expected = case.expected?.let { expected ->
            requireNotNull(catalog.reading(expected).toDraft()) {
                "La tarjeta esperada de ${case.id} no tiene operación"
            }.withExtra(case.extra, catalog)
        } ?: initial.takeIf { case.screen }
        // En la pantalla entera, una tarjeta que quedó igual cuando debía cambiar no se aplicó.
        val unchanged = case.screen && got?.loose() == initial.loose()
        val verdict = when {
            unchanged && expected?.loose() != initial.loose() -> CorrectionVerdict.NOT_APPLIED
            got == null && expected == null -> CorrectionVerdict.RIGHT
            got == null -> CorrectionVerdict.NOT_APPLIED
            expected != null && got.loose() == expected.loose() -> CorrectionVerdict.RIGHT
            else -> CorrectionVerdict.WRONG
        }
        return CorrectionResult(
            code,
            case,
            render(expected, catalog),
            render(got, catalog),
            verdict,
        )
    }

    /**
     * La tarjeta que deja la pantalla cuando la frase no la corrige: la lee entera y la suma, o la
     * deja como estaba si pregunta, avisa o no la entiende. Solo "no" con otra operación la
     * reemplaza.
     */
    private fun followed(
        initial: PhraseDraft,
        case: Correction,
        language: PhraseLanguage,
        catalog: FixtureCatalog,
    ): PhraseDraft {
        val reading = PhraseInterpreter.read(
            case.correction,
            language,
            catalog.style,
            catalog.moneyDecimals,
            catalog.catalog,
        )
        val next = initial.followedBy(
            case.correction,
            reading,
            language,
            catalog.style,
            catalog.moneyDecimals,
            catalog.catalog,
        )
        return when (next) {
            is CardFollowUp.Joined -> next.draft

            is CardFollowUp.Replaced -> next.draft

            is CardFollowUp.KnownProduct,
            is CardFollowUp.OtherOperation,
            CardFollowUp.NotUnderstood,
            -> initial
        }
    }

    private companion object {
        const val FIXTURE = "src/test/resources/phrase/fixture.json"
        const val GUIDE_DIR = "src/test/resources/phrase/guide"
        const val REPORT = "build/phrase-corrections-report.md"
        val LANGUAGES = listOf(
            "es" to PhraseLanguage.ES,
            "en" to PhraseLanguage.EN,
            "pt" to PhraseLanguage.PT,
        )
    }
}

/**
 * Un caso de corrección de la guía. [expected] es `null` si no debe aplicarse. Con [screen], se mide
 * como la pantalla entera: ver la prueba.
 */
private data class Correction(
    val id: String,
    val initialText: String,
    val initial: Expectation,
    val correction: String,
    val tags: List<String>,
    val expected: Expectation?,
    val extra: Map<String, Json>,
    val screen: Boolean,
) {
    val isOdd: Boolean get() = (id.takeLastWhile(Char::isDigit).toIntOrNull() ?: 0) % 2 == 1

    companion object {
        fun of(obj: Map<String, Json>): Correction {
            val applies = (obj["applies"] as? Json.Bool)?.value ?: false
            return Correction(
                id = obj.getValue("id").str(),
                initialText = obj["initial"]?.strOrNull().orEmpty(),
                initial = Expectation.of(obj.getValue("initial_expect").obj()),
                correction = obj.getValue("correction").str(),
                tags = obj["tags"]?.arrOrNull()?.mapNotNull { it.strOrNull() }.orEmpty(),
                expected = obj["expect"]?.objOrNull()?.takeIf { applies }?.let(Expectation::of),
                extra = obj["extra"]?.objOrNull().orEmpty(),
                screen = (obj["screen"] as? Json.Bool)?.value ?: false,
            )
        }
    }
}

/**
 * La tarjeta esperada con lo que `extra` dice del producto nuevo: va al producto que se crea o, si
 * no hay, a la última línea con un producto nuevo.
 */
private fun PhraseDraft.withExtra(extra: Map<String, Json>, catalog: FixtureCatalog): PhraseDraft {
    if (extra.isEmpty()) return this
    val usage = extra["usage"]?.strOrNull()?.let(ProductUsage::valueOf)
    val category = extra["product_category"]?.strOrNull()
    val categoryId = category?.let(catalog::productCategoryNamed)
    val said = NewProductTraits(
        usage = usage,
        categoryId = categoryId,
        newCategory = category.takeIf { categoryId == null },
    )
    val target = target
    if (target is DraftTarget.New) {
        return copy(
            target = target.copy(traits = target.traits.with(said)),
        )
    }
    val index = lines.indexOfLast { it.product is DraftTarget.New }
    if (index < 0) return this
    return copy(
        lines = lines.mapIndexed { at, line ->
            val product = line.product
            if (at == index && product is DraftTarget.New) {
                line.copy(product = product.copy(traits = product.traits.with(said)))
            } else {
                line
            }
        },
    )
}

/** La tarjeta con los nombres nuevos sin tildes, mayúsculas ni plurales, para compararla. */
private fun PhraseDraft.loose(): PhraseDraft = copy(
    lines = lines.map { it.copy(product = it.product?.loose()) },
    target = target?.loose(),
)

private fun DraftTarget.loose(): DraftTarget = when (this) {
    is DraftTarget.Existing -> this

    is DraftTarget.New -> copy(
        name = looseName(name).joinToString(" "),
        traits = traits.copy(
            newCategory = traits.newCategory?.let {
                looseName(it).joinToString(" ")
            },
        ),
    )
}

/** La tarjeta con las claves del catálogo, para leerla en el informe. */
private fun render(draft: PhraseDraft?, catalog: FixtureCatalog): String {
    if (draft == null) return "se lee como frase nueva"
    fun key(id: Long?) = id?.let(catalog::keyOf)
    val parts = listOfNotNull(
        draft.intent.name,
        draft.reason?.name,
        draft.lines.takeIf {
            it.isNotEmpty()
        }?.joinToString(" + ", "[", "]") { renderLine(it, catalog) },
        key(draft.paymentMethodId)?.let { "pago $it" },
        key(draft.partyId)?.let { "persona $it" },
        key(draft.categoryId)?.let { "categoría $it" },
        draft.total?.let { "total ${it.minor}" },
        "a crédito".takeIf { draft.credit },
        draft.target?.let { "producto ${renderTarget(it, catalog)}" },
        draft.targetQuantity?.let { "rinde ${it.milli}" },
        draft.price?.let { "precio ${it.minor}" },
        draft.cost?.let { "costo ${it.minor}" },
        key(draft.unitId)?.let { "unidad $it" },
    )
    return parts.joinToString(" · ")
}

private fun renderLine(line: DraftLine, catalog: FixtureCatalog): String = listOfNotNull(
    line.quantity?.milli?.let { "${it}m" },
    line.unitId?.let(catalog::keyOf),
    line.product?.let { renderTarget(it, catalog) } ?: "(elegir)",
    line.unitPrice?.let { "a ${it.minor}" },
    line.lineTotal?.let { "por ${it.minor}" },
).joinToString(" ")

private fun renderTarget(target: DraftTarget, catalog: FixtureCatalog): String = when (target) {
    is DraftTarget.Existing -> catalog.keyOf(target.productId)

    is DraftTarget.New -> buildString {
        append("crear «${target.name}»")
        target.traits.usage?.let { append(" uso $it") }
        target.traits.stockMode?.let { append(" stock $it") }
        target.traits.categoryId?.let { append(" categoría ${catalog.keyOf(it)}") }
        target.traits.newCategory?.let { append(" categoría nueva «$it»") }
    }
}

private enum class CorrectionVerdict(val label: String) {
    RIGHT("Bien"),
    NOT_APPLIED("Sin aplicar"),
    WRONG("Mal aplicada"),
}

private data class CorrectionResult(
    val language: String,
    val case: Correction,
    val expected: String,
    val got: String,
    val verdict: CorrectionVerdict,
)

private class CorrectionReport(private val results: List<CorrectionResult>) {
    private val even = results.filterNot { it.case.isOdd }
    private val odd = results.filter { it.case.isOdd }

    fun summary(): String = "Correcciones pares: ${counts(even)}. Impares: ${counts(odd)}."

    private fun counts(items: List<CorrectionResult>): String = "${items.size} casos, " +
        CorrectionVerdict.entries.joinToString(", ") { verdict ->
            "${verdict.label} ${items.count { it.verdict == verdict }}"
        }

    fun markdown(): String = buildString {
        appendLine("# Medición de las correcciones con la tarjeta abierta")
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
            even.flatMap { result -> result.case.tags.map { it to result } }
                .groupBy({ it.first }, { it.second }),
        )
        appendLine("## Pares que no salieron bien")
        appendLine()
        for (result in even.filter { it.verdict != CorrectionVerdict.RIGHT }
            .sortedByDescending { it.verdict.ordinal }) {
            appendLine("### ${result.verdict.label} · ${result.language} · ${result.case.id}")
            appendLine()
            appendLine("> ${result.case.initialText} → ${result.case.correction}")
            appendLine()
            appendLine("Etiquetas: ${result.case.tags.joinToString(", ")}")
            appendLine()
            appendLine("- Esperado: ${result.expected}")
            appendLine("- Obtenido: ${result.got}")
            appendLine()
        }
    }

    private fun StringBuilder.table(groups: Map<String, List<CorrectionResult>>) {
        val header = CorrectionVerdict.entries.joinToString(" | ") { it.label }
        appendLine("| Grupo | Casos | $header | Bien (%) |")
        appendLine("|---|---:|" + CorrectionVerdict.entries.joinToString("") { "---:|" } + "---:|")
        for ((group, items) in groups.toSortedMap()) {
            val counts = CorrectionVerdict.entries.map { verdict ->
                items.count { it.verdict == verdict }
            }
            val right = counts[CorrectionVerdict.RIGHT.ordinal]
            val share = if (items.isEmpty()) "-" else "${right * 100 / items.size} %"
            appendLine("| $group | ${items.size} | ${counts.joinToString(" | ")} | $share |")
        }
        appendLine()
    }
}
