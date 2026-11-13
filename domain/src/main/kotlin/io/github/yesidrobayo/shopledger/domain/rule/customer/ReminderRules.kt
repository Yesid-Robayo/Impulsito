package io.github.yesidrobayo.shopledger.domain.rule.customer

/** Por qué una plantilla de recordatorio no se puede guardar. */
enum class ReminderTemplateIssue {
    /** Está vacía: un recordatorio siempre dice algo. */
    EMPTY,

    /** Pasa de 1000 caracteres. */
    TOO_LONG,
}

/**
 * Lo que va en los marcadores de un recordatorio, ya escrito en el formato del negocio: el nombre del
 * cliente, lo que debe con su moneda, el nombre del negocio y, si lo hay, el vencimiento.
 */
data class ReminderValues(
    val customer: String,
    val amount: String,
    val business: String,
    val dueDate: String?,
)

/**
 * Las reglas del recordatorio de pago uno a uno. La plantilla es por idioma y la persona la edita;
 * sus marcadores se llenan al enviar, y el mensaje sale siempre por otra app, por un toque de la
 * persona: aquí no se envía nada.
 *
 * Los marcadores son `{customer}`, `{amount}`, `{business}` y `{due_date}`. `{balance}` vale lo
 * mismo que `{amount}`, para que una plantilla escrita con cualquiera de los dos nombres funcione.
 * Un marcador sin valor, como `{due_date}` sin vencimiento, queda vacío.
 */
object ReminderRules {
    /** Lo más largo que puede ser una plantilla. */
    const val MAX_LENGTH: Int = 1_000

    /** El nombre de la plantilla del recordatorio de pago en la tabla de plantillas. */
    const val PAYMENT_REMINDER: String = "PAYMENT_REMINDER"

    /** Por qué [template] no se puede guardar, o `null` si se puede. */
    fun check(template: String): ReminderTemplateIssue? = when {
        template.isBlank() -> ReminderTemplateIssue.EMPTY
        template.length > MAX_LENGTH -> ReminderTemplateIssue.TOO_LONG
        else -> null
    }

    /** El mensaje de [template] con [values] en sus marcadores, y [details] al final si los hay. */
    fun fill(template: String, values: ReminderValues, details: String?): String {
        val filled = template
            .replace("{customer}", values.customer)
            .replace("{amount}", values.amount)
            .replace("{balance}", values.amount)
            .replace("{business}", values.business)
            .replace("{due_date}", values.dueDate.orEmpty())
            .trim()
        return if (details.isNullOrBlank()) filled else filled + "\n\n" + details.trim()
    }
}
