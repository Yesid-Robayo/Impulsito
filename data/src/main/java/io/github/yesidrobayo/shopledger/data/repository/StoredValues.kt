package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlin.time.Instant

// Cómo pasan los valores del dominio a sus columnas y de vuelta. Todo lo que se lee se trata como
// dato de la base: un valor que sus guardas no habrían dejado entrar no se adivina, se rechaza.

/** Milisegundos en un segundo: la base guarda los instantes con esa precisión. */
private const val MILLIS_PER_SECOND = 1_000L

/** Nanosegundos en un milisegundo. */
private const val NANOS_PER_MILLI = 1_000_000

/**
 * El instante como se guarda: milisegundos desde 1970-01-01T00:00Z, **truncados** hacia el pasado.
 *
 * El dominio puede traer nanosegundos, y la base solo guarda milisegundos. La fracción se descarta
 * siempre hacia abajo, también antes de 1970: así un instante nunca se guarda como uno posterior al
 * que ocurrió, y dos instantes del mismo milisegundo dan la misma columna. Se calcula a mano, y no
 * con la conversión de la biblioteca, porque esa satura en silencio al salirse de `Long`: aquí un
 * instante que no cabe lanza [ArithmeticException], y la transacción se deshace.
 */
internal fun Instant.toStoredMillis(): Long = Math.addExact(
    Math.multiplyExact(epochSeconds, MILLIS_PER_SECOND),
    (nanosecondsOfSecond / NANOS_PER_MILLI).toLong(),
)

/** El instante que se guardó como [this] milisegundos. */
internal fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)

/** El texto `AAAA-MM-DD` con que se guarda una fecha de negocio. */
internal fun BusinessDate.toStored(): String = toString()

/** La fecha de negocio guardada en [text]. */
internal fun storedDate(text: String): BusinessDate = BusinessDate.parse(text) ?: brokenRow()

/** Los extremos de [this] como texto guardado, para las consultas por rango de fechas. */
internal val ReportPeriod.storedStart: String get() = start.toStored()
internal val ReportPeriod.storedEnd: String get() = end.toStored()

/** Un booleano como se guarda: SQLite no los tiene, y van como 0 o 1. */
internal fun Boolean.toStoredFlag(): Long = if (this) 1L else 0L

/** El booleano guardado como 0 o 1. Otro valor no es un booleano. */
internal fun Long.toFlag(): Boolean = when (this) {
    0L -> false
    1L -> true
    else -> brokenRow()
}

/**
 * La constante de [E] guardada con el nombre [name]. Compara el nombre exacto: los nombres
 * guardados no cambian nunca, y uno desconocido es una fila que sus guardas no admiten.
 */
internal inline fun <reified E : Enum<E>> storedEnum(name: String): E =
    enumValues<E>().firstOrNull { it.name == name } ?: brokenRow()

/** Un entero guardado que el dominio lleva en `Int`, como un plazo en días. */
internal fun Long.toStoredInt(): Int = if (this in
    Int.MIN_VALUE..Int.MAX_VALUE
) {
    toInt()
} else {
    brokenRow()
}

/**
 * Una fila que rompe una regla de la base: un valor guardado que sus guardas no admiten, una
 * escritura que no encontró su fila o un saldo que no cuadra. Es un error de programación o una base
 * alterada fuera de la app, y no se reintenta. Sale sin texto, como toda falla de la base.
 */
internal fun brokenRow(): Nothing =
    throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

/**
 * Exige que una escritura de una fila conocida la haya cambiado. Con 0 la fila no existe, o ya no
 * estaba en el estado que la escritura pide, como una venta ya anulada: confirmar seguiría como si
 * nada, y la transacción entera debe deshacerse.
 */
internal fun requireOneRow(changed: Int) {
    if (changed != 1) brokenRow()
}

/**
 * La anulación guardada de una venta, una compra o un gasto, o `null` si está activo. El estado y
 * los datos de la anulación van en columnas separadas, y tienen que decir lo mismo: activo sin
 * datos, o anulado con instante y motivo.
 */
internal fun storedVoiding(
    status: String,
    voidedAt: Long?,
    reason: String?,
    note: String?,
): Voiding? = when (storedEnum<DocumentStatus>(status)) {
    DocumentStatus.ACTIVE -> if (voidedAt == null && reason == null &&
        note == null
    ) {
        null
    } else {
        brokenRow()
    }

    DocumentStatus.VOIDED ->
        Voiding(
            voidedAt = (voidedAt ?: brokenRow()).toInstant(),
            reason = storedEnum<VoidReason>(reason ?: brokenRow()),
            note = note,
        )
}
