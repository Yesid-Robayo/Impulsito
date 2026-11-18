package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage

/**
 * Las palabras con que una frase corta corrige la tarjeta, en un idioma. Se normalizan igual que
 * el resto del vocabulario, así que tildes y mayúsculas no importan. Ampliar lo que se entiende es
 * agregar frases a estas listas; ninguna regla de la corrección nombra una palabra.
 *
 * - [leads] solo avisan que viene una corrección y se saltan al principio: "no", "perdón".
 * - [copulas] separan lo que se nombra de lo que era: "la harina **era** de maíz".
 * - [removals] abren una línea que sale: "quita las gaseosas".
 * - [additions] abren una línea que se suma: "agrega 2 huevos". Las palabras que separan líneas en
 *   una frase ("y", "también") también suman, pero con más cuidado: ver la corrección.
 * - [others] dicen que lo nombrado es de más y se suma a lo que ya estaba: "y **otros** 2 panes",
 *   "2 panes **más**". Sin ellas, un conteo o una receta corrigen el producto repetido.
 * - [renames] abren el nombre nuevo del producto que se va a crear: "se llama harina integral".
 * - [operations] nombran la operación que era: "fue una **compra**".
 */
internal class CorrectionVocabulary(
    leads: List<String>,
    copulas: List<String>,
    removals: List<String>,
    additions: List<String>,
    others: List<String>,
    renames: List<String>,
    operations: Map<String, PhraseIntent>,
) {
    val leads: PhraseTable<Unit> = PhraseTable.of(leads)
    val copulas: PhraseTable<Unit> = PhraseTable.of(copulas)
    val removals: PhraseTable<Unit> = PhraseTable.of(removals)
    val additions: PhraseTable<Unit> = PhraseTable.of(additions)
    val others: PhraseTable<Unit> = PhraseTable.of(others)
    val renames: PhraseTable<Unit> = PhraseTable.of(renames)
    val operations: PhraseTable<PhraseIntent> = PhraseTable(operations.toList())
}

/** El vocabulario de corrección de cada idioma, compilado una sola vez. */
internal object CorrectionVocabularies {
    private val spanish = CorrectionVocabulary(
        leads = listOf(
            "no", "nono", "perdón", "perdon", "disculpa", "disculpá", "mejor", "digo", "más bien",
            "en realidad", "corrijo", "corrección", "quise decir", "me equivoqué", "espera",
            "esperá", "error",
        ),
        copulas = listOf(
            "era", "eran", "fue", "fueron", "es", "son", "sería", "serían", "eso era", "eso fue",
            "eso es",
        ),
        removals = listOf(
            "quita", "quitá", "quite", "quitar", "quítale", "saca", "sacá", "saque", "sacar",
            "borra", "borrá", "borre", "borrar", "elimina", "eliminá", "elimine", "eliminar",
            "sin", "ya no", "cancela", "anula",
        ),
        additions = listOf(
            "agrega", "agregá", "agregue", "agregar", "agrégale", "añade", "añadí", "añadir",
            "suma", "sumá", "súmale", "sumar", "pon", "poné", "ponle", "ponme", "incluye",
            "incluí", "incluir", "falta", "faltó", "faltan", "faltaron", "me faltó", "se me olvidó",
        ),
        others = listOf("otro", "otra", "otros", "otras", "más"),
        renames = listOf(
            "se llama",
            "se llamaba",
            "llámalo",
            "llámala",
            "ponle de nombre",
            "el nombre es",
            "de nombre",
            "su nombre es",
        ),
        operations = mapOf(
            "compra" to PhraseIntent.PURCHASE,
            "venta" to PhraseIntent.SALE,
        ),
    )

    private val english = CorrectionVocabulary(
        leads = listOf(
            "no", "nope", "sorry", "wait", "oops", "i meant", "my bad", "correction", "rather",
            "make that", "make it", "change it to", "change that to",
        ),
        copulas = listOf(
            "was", "were", "is", "are", "its", "it was", "it is", "they were", "that was",
            "should be", "should have been", "it should be",
        ),
        removals = listOf(
            "remove", "delete", "take out", "take off", "drop", "without", "minus", "cancel",
            "scratch", "forget",
        ),
        additions = listOf(
            "add", "also add", "add also", "and add", "plus add", "include", "put", "throw in",
            "forgot", "i forgot", "missing",
        ),
        others = listOf("another", "other", "others", "more"),
        renames = listOf(
            "its called",
            "it is called",
            "call it",
            "name it",
            "the name is",
            "its name is",
            "it was called",
        ),
        operations = mapOf(
            "purchase" to PhraseIntent.PURCHASE,
            "sale" to PhraseIntent.SALE,
        ),
    )

    private val portuguese = CorrectionVocabulary(
        leads = listOf(
            "não", "nao", "desculpa", "desculpe", "perdão", "na verdade", "aliás", "quer dizer",
            "quero dizer", "melhor", "ops", "espera", "corrigindo", "errei",
        ),
        // "é" queda fuera: sin tilde es "e", que une dos líneas.
        copulas = listOf("era", "eram", "foi", "foram", "são", "seria", "seriam", "isso era"),
        removals = listOf(
            "tira", "tire", "tirar", "remove", "remova", "remover", "apaga", "apague", "sem",
            "retira", "retire", "exclui", "exclua", "cancela",
        ),
        additions = listOf(
            "adiciona", "adicione", "adicionar", "acrescenta", "acrescente", "acrescentar", "põe",
            "ponha", "coloca", "coloque", "inclui", "inclua", "bota", "faltou", "esqueci",
        ),
        others = listOf("outro", "outra", "outros", "outras", "mais"),
        renames = listOf("se chama", "chama", "o nome é", "chame de", "o nome dele é"),
        operations = mapOf(
            "compra" to PhraseIntent.PURCHASE,
            "venda" to PhraseIntent.SALE,
        ),
    )

    fun of(language: PhraseLanguage): CorrectionVocabulary = when (language) {
        PhraseLanguage.ES -> spanish
        PhraseLanguage.EN -> english
        PhraseLanguage.PT -> portuguese
    }
}
