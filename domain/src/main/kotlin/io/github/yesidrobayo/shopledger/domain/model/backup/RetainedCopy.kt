package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Una copia de WebDAV tal como la ve la poda: lo que dicen su nombre y sus metadatos ya
 * validados. Nunca lleva datos del negocio, solo conteos.
 *
 * @property key su nombre en el servicio: identifica la copia y desempata el orden.
 * @property createdAt el instante UTC de su nombre, que da su día.
 * @property valid si el validador no encontró nada al crearla.
 * @property fatal si la app no la puede restaurar.
 * @property pinned si está fijada: nunca se poda.
 * @property keyId la clave con que se cifró, o nula si va en claro.
 * @property records sus registros, de las tablas que solo crecen.
 */
data class RetainedCopy(
    val key: String,
    val generation: Long,
    val deviceId: String,
    val createdAt: CopyInstant,
    val valid: Boolean,
    val fatal: Boolean,
    val pinned: Boolean,
    val keyId: String?,
    val records: Long,
)

/**
 * Lo que la poda necesita.
 *
 * @property copies las copias que cuentan. La copia nueva ya está entre ellas.
 * @property deviceId esta instalación.
 * @property currentKeyId la clave vigente, o nula sin cifrado.
 * @property fullVerifyGeneration la generación de la última copia leída completa, o nula.
 * @property damagedGenerations las copias confirmadas que una lectura completa no pudo comprobar:
 *   cuentan como inválidas para los días, la cabeza y la clave.
 */
data class RetentionInput(
    val copies: List<RetainedCopy>,
    val deviceId: String,
    val currentKeyId: String?,
    val fullVerifyGeneration: Long?,
    val damagedGenerations: Set<Long>,
)

/**
 * Lo que decidió la poda.
 *
 * @property keep lo que se conserva, de mayor a menor generación.
 * @property prune lo que se poda, de la más vieja a la más nueva: si una ejecución no alcanza a
 *   borrar todo, quedan las más recientes.
 * @property blockedByRecords si la poda se detuvo porque una copia por podar tiene más registros
 *   que la cabeza sin una fijada que los cubra. Entonces [prune] está vacía, y la persona lo ve.
 */
data class RetentionPlan(
    val keep: List<RetainedCopy>,
    val prune: List<RetainedCopy>,
    val blockedByRecords: Boolean,
)
