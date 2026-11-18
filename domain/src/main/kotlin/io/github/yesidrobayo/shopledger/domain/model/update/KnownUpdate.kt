package io.github.yesidrobayo.shopledger.domain.model.update

/**
 * La versión más nueva que dio la última búsqueda que leyó un aviso firmado. Solo informa: con
 * ella `Inicio` dice que hay una versión nueva hasta que esté instalada. Nunca descarga ni
 * instala nada.
 *
 * @property versionCode el número de compilación de esa versión.
 * @property versionName su nombre, como `1.0.7`.
 */
data class KnownUpdate(val versionCode: Long, val versionName: String)
