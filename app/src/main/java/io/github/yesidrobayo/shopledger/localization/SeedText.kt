package io.github.yesidrobayo.shopledger.localization

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable

/**
 * El texto visible de las filas iniciales de los catálogos: su nombre si la persona lo editó, o el
 * recurso traducible de su clave.
 *
 * Una fila inicial nace con su clave estable y sin nombre, así que mientras nadie la renombre se ve
 * en el idioma de la interfaz, y cambiar de idioma la cambia. Renombrada, se ve con el nombre de la
 * persona en cualquier idioma, y la clave sigue identificándola.
 *
 * **El idioma sale de [Resources], que da quien llama:** el de la actividad, o el del contexto con
 * el idioma elegido para la app fuera de ella. Nunca el contexto de la aplicación en Android 12 o
 * anterior, que sigue el idioma del teléfono.
 *
 * Cada clave apunta a su recurso por una tabla escrita aquí, no por un nombre armado en tiempo de
 * ejecución: así el compilador comprueba que el recurso existe, y la optimización de recursos no
 * lo descarta. Una prueba comprueba que el nombre de cada uno es `seed_<tabla>_<clave>`, en
 * minúsculas, y que [SeedTable] no tiene ninguna clave sin texto.
 */
object SeedText {
    /**
     * El texto visible de una fila de [table]: [name] si la persona lo escribió, o el recurso de
     * [systemKey].
     *
     * Una clave que esta versión no conoce, como la de una copia hecha por una versión más nueva, se
     * muestra tal cual: es mejor que una fila en blanco, y sigue siendo única en su tabla. Lanza
     * [IllegalArgumentException] si la fila no tiene ni nombre ni clave, que la guarda de la base no
     * deja guardar.
     */
    fun displayName(
        resources: Resources,
        table: SeedTable,
        systemKey: String?,
        name: String?,
    ): String {
        if (name != null) return name
        val key = requireNotNull(systemKey) { "Una fila sin nombre necesita su clave inicial" }
        val res = nameRes(table, key) ?: return key
        return resources.getString(res)
    }

    /**
     * El símbolo de una unidad: [symbol] si la persona lo escribió, el recurso de [systemKey] si es
     * una unidad inicial, o `null` si no tiene. Sin símbolo, una cantidad se escribe con el nombre
     * de la unidad.
     */
    fun unitSymbol(resources: Resources, systemKey: String?, symbol: String?): String? {
        if (symbol != null) return symbol
        val res = systemKey?.let(::unitSymbolRes) ?: return null
        return resources.getString(res)
    }

    /** El recurso del nombre de la fila inicial [systemKey] de [table], o `null` si no la hay. */
    @StringRes
    fun nameRes(table: SeedTable, systemKey: String): Int? = when (table) {
        SeedTable.EXPENSE_CATEGORIES -> expenseCategoryRes(systemKey)
        SeedTable.UNITS -> unitRes(systemKey)
        SeedTable.PAYMENT_METHODS -> paymentMethodRes(systemKey)
        SeedTable.PRICE_LISTS -> onlyKey(systemKey, "DEFAULT", R.string.seed_price_lists_default)
        SeedTable.LOCATIONS -> onlyKey(systemKey, "MAIN", R.string.seed_locations_main)
        SeedTable.MONEY_ACCOUNTS -> onlyKey(systemKey, "CASH", R.string.seed_money_accounts_cash)
    }

    /** El recurso del símbolo de la unidad inicial [systemKey], o `null` si no la hay. */
    @StringRes
    fun unitSymbolRes(systemKey: String): Int? = when (systemKey) {
        "UNIT" -> R.string.unit_symbol_unit
        "KILOGRAM" -> R.string.unit_symbol_kilogram
        "GRAM" -> R.string.unit_symbol_gram
        "LITER" -> R.string.unit_symbol_liter
        "MILLILITER" -> R.string.unit_symbol_milliliter
        "POUND" -> R.string.unit_symbol_pound
        "OUNCE" -> R.string.unit_symbol_ounce
        "GALLON" -> R.string.unit_symbol_gallon
        "METER" -> R.string.unit_symbol_meter
        "BOX" -> R.string.unit_symbol_box
        "PACK" -> R.string.unit_symbol_pack
        "DOZEN" -> R.string.unit_symbol_dozen
        else -> null
    }

    /**
     * El recurso con el patrón plural de ICU de la unidad inicial [systemKey], o `null` si no la
     * hay: una unidad de la persona no tiene plural conocido.
     */
    @StringRes
    fun unitQuantityRes(systemKey: String): Int? = when (systemKey) {
        "UNIT" -> R.string.unit_qty_unit
        "KILOGRAM" -> R.string.unit_qty_kilogram
        "GRAM" -> R.string.unit_qty_gram
        "LITER" -> R.string.unit_qty_liter
        "MILLILITER" -> R.string.unit_qty_milliliter
        "POUND" -> R.string.unit_qty_pound
        "OUNCE" -> R.string.unit_qty_ounce
        "GALLON" -> R.string.unit_qty_gallon
        "METER" -> R.string.unit_qty_meter
        "BOX" -> R.string.unit_qty_box
        "PACK" -> R.string.unit_qty_pack
        "DOZEN" -> R.string.unit_qty_dozen
        else -> null
    }

    /** [res] si [systemKey] es [key], la única fila inicial de su tabla, o `null` si no. */
    @StringRes
    private fun onlyKey(systemKey: String, key: String, @StringRes res: Int): Int? =
        if (systemKey == key) res else null

    private fun expenseCategoryRes(systemKey: String): Int? = when (systemKey) {
        "RENT" -> R.string.seed_expense_categories_rent
        "UTILITIES" -> R.string.seed_expense_categories_utilities
        "INTERNET_PHONE" -> R.string.seed_expense_categories_internet_phone
        "TRANSPORT" -> R.string.seed_expense_categories_transport
        "PACKAGING_SUPPLIES" -> R.string.seed_expense_categories_packaging_supplies
        "ADVERTISING" -> R.string.seed_expense_categories_advertising
        "TAXES_FEES" -> R.string.seed_expense_categories_taxes_fees
        "WAGES" -> R.string.seed_expense_categories_wages
        "OTHER" -> R.string.seed_expense_categories_other
        else -> null
    }

    private fun unitRes(systemKey: String): Int? = when (systemKey) {
        "UNIT" -> R.string.seed_units_unit
        "KILOGRAM" -> R.string.seed_units_kilogram
        "GRAM" -> R.string.seed_units_gram
        "LITER" -> R.string.seed_units_liter
        "MILLILITER" -> R.string.seed_units_milliliter
        "POUND" -> R.string.seed_units_pound
        "OUNCE" -> R.string.seed_units_ounce
        "GALLON" -> R.string.seed_units_gallon
        "METER" -> R.string.seed_units_meter
        "BOX" -> R.string.seed_units_box
        "PACK" -> R.string.seed_units_pack
        "DOZEN" -> R.string.seed_units_dozen
        else -> null
    }

    private fun paymentMethodRes(systemKey: String): Int? = when (systemKey) {
        "CASH" -> R.string.seed_payment_methods_cash
        "CARD" -> R.string.seed_payment_methods_card
        "TRANSFER" -> R.string.seed_payment_methods_transfer
        "WALLET" -> R.string.seed_payment_methods_wallet
        "ON_ACCOUNT" -> R.string.seed_payment_methods_on_account
        "OTHER" -> R.string.seed_payment_methods_other
        else -> null
    }
}
