package io.github.yesidrobayo.shopledger.domain.model.money

/**
 * Lo que la persona escribe de una cuenta de dinero.
 *
 * La cuenta inicial de efectivo nace sin nombre propio: se muestra con el texto traducido de su clave
 * hasta que la persona la renombra. Por eso [name] y [normalizedName] pueden faltar, pero solo en una
 * cuenta con clave inicial, y faltan los dos o ninguno.
 */
data class MoneyAccountDetails(
    val name: String?,
    val normalizedName: String?,
    val kind: MoneyAccountKind,
)
