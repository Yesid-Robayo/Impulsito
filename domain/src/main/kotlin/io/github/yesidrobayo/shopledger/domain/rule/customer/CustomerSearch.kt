package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Busca clientes por nombre o por teléfono, como se escriben en el mostrador.
 *
 * El nombre se compara con su forma normalizada, sin mayúsculas ni tildes, así que `jose` encuentra
 * a `José`. El teléfono se compara solo por sus dígitos, en cualquier sistema de escritura y sin
 * espacios ni signos: `300 12` encuentra `(300) 123`. Un cliente anonimizado ya no tiene nombre ni
 * teléfono, así que ninguna búsqueda lo encuentra. Una búsqueda en blanco los deja a todos.
 */
object CustomerSearch {
    /** Los de [customers] que coinciden con [query], en el mismo orden. */
    fun filter(customers: List<Customer>, query: String): List<Customer> {
        val name = TextNormalizer.normalize(query.trim())
        if (name.isEmpty()) return customers
        val digits = digitsOf(query)
        return customers.filter { matches(it, name, digits) }
    }

    /** Si [customer] coincide con [query]. */
    fun matches(customer: Customer, query: String): Boolean {
        val name = TextNormalizer.normalize(query.trim())
        return name.isEmpty() || matches(customer, name, digitsOf(query))
    }

    private fun matches(customer: Customer, name: String, digits: String): Boolean {
        val contact = customer.contact ?: return false
        if (contact.normalizedName.contains(name)) return true
        val phone = contact.phone ?: return false
        return digits.isNotEmpty() && digitsOf(phone).contains(digits)
    }

    /** Los dígitos de [text], pasados a 0 a 9 sin importar el sistema de escritura. */
    private fun digitsOf(text: String): String = buildString {
        text.forEach { if (it.isDigit()) append(it.digitToInt()) }
    }
}
