package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthRow
import io.github.yesidrobayo.shopledger.domain.port.DataHealthSource
import io.github.yesidrobayo.shopledger.domain.rule.backup.DataHealthRule

/**
 * `Salud de mis datos`: las filas del panel con el estado del respaldo de ahora. Solo lee: ninguna
 * fila escribe nada.
 */
class ReadDataHealthUseCase(private val source: DataHealthSource) {
    /**
     * @param pinned con WebDAV, las copias fijadas de la lista a la vista, o nulo si no se pudo ver.
     * @return las filas, o nulo si la base no abre.
     */
    suspend operator fun invoke(overview: BackupOverview, pinned: Int?): List<HealthRow>? {
        val facts = source.facts() ?: return null
        return DataHealthRule.rows(overview, facts, pinned)
    }
}
