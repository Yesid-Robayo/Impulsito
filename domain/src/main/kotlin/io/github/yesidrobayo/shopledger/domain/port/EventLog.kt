package io.github.yesidrobayo.shopledger.domain.port

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * El único punto de registro de la app. Nadie más escribe en el registro del sistema.
 *
 * Lo que se registra puede leerlo otra app con permisos de depuración, aparecer en un informe de
 * fallos o quedar en una captura de soporte. Por eso una línea nunca lleva montos, cantidades,
 * nombres, teléfonos, correos, direcciones, notas, contenido de la base, contraseñas, claves, la
 * dirección del servicio de respaldo ni el mensaje de una excepción. Solo admite conteos,
 * duraciones, códigos, estados e identificadores numéricos internos.
 *
 * Los tipos lo hacen cumplir:
 * - El evento es un valor de un `enum` que implementa [LogEvent], con su texto fijo.
 * - Cada dato es un [LogField], que solo se crea con números, estados y códigos.
 * - De una excepción solo se registra la clase, y el tipo si es [StorageFailureException].
 * - La línea, [LogLine], solo la arma este módulo: quien implementa [write] la recibe hecha.
 *
 * Se usa con las funciones [info], [warn], [error], [contain] y [failureHandler]. Lo implementa la
 * capa de datos sobre el registro de Android, y las pruebas, con una lista en memoria.
 */
interface EventLog {
    /** Escribe una línea ya armada. Nunca lanza: registrar no puede detener la app. */
    fun write(level: LogLevel, line: LogLine)
}

/** Registra [event] como información. En la app publicada no queda. */
fun <E> EventLog.info(event: E, vararg fields: LogField) where E : Enum<E>, E : LogEvent =
    write(LogLevel.INFO, LogLine(event, fields.asList(), failure = null))

/** Registra [event] como aviso: algo raro que la app resolvió. */
fun <E> EventLog.warn(event: E, vararg fields: LogField) where E : Enum<E>, E : LogEvent =
    write(LogLevel.WARN, LogLine(event, fields.asList(), failure = null))

/** Registra [event] como aviso, con la clase de [failure] y nunca su mensaje. */
fun <E> EventLog.warn(
    event: E,
    failure: Throwable,
    vararg fields: LogField,
) where E : Enum<E>, E : LogEvent = write(LogLevel.WARN, LogLine(event, fields.asList(), failure))

/** Registra [event] como error. */
fun <E> EventLog.error(event: E, vararg fields: LogField) where E : Enum<E>, E : LogEvent =
    write(LogLevel.ERROR, LogLine(event, fields.asList(), failure = null))

/** Registra [event] como error, con la clase de [failure] y nunca su mensaje. */
fun <E> EventLog.error(
    event: E,
    failure: Throwable,
    vararg fields: LogField,
) where E : Enum<E>, E : LogEvent = write(LogLevel.ERROR, LogLine(event, fields.asList(), failure))

/**
 * Corre [block] sin que ninguna excepción salga de él: es la última red de un trabajo en segundo
 * plano.
 *
 * Si un trabajo deja salir una excepción, la biblioteca que lo corre puede registrarla con su
 * mensaje, y el mensaje de una conexión o de la base puede traer la dirección del servidor o datos
 * de una fila. Aquí la excepción se registra como [event], con su clase, y se devuelve
 * [Contained.Failed]. Solo la cancelación sigue su camino: la corrutina debe terminar.
 *
 * Atrapa también los errores graves, como quedarse sin memoria: el trabajo termina igual, sin su
 * mensaje en el registro.
 */
suspend fun <E, T> EventLog.contain(
    event: E,
    block: suspend () -> T,
): Contained<T> where E : Enum<E>, E : LogEvent = try {
    Contained.Done(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    error(event, failure)
    Contained.Failed(LogField.failureClassName(failure))
}

/**
 * El manejador de las corrutinas de fondo: una falla que nadie atrapó se registra como [event], con
 * su clase, y no tumba la app. Sin él, la falla llegaría al manejador del sistema, que escribe el
 * mensaje y la traza en el registro antes de cerrar la app.
 */
fun <E> EventLog.failureHandler(
    event: E,
): CoroutineExceptionHandler where E : Enum<E>, E : LogEvent =
    CoroutineExceptionHandler { _, failure -> error(event, failure) }
