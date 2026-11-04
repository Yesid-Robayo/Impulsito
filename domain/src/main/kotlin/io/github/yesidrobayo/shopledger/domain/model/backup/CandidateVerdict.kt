package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Lo que concluye el validador sobre una base candidata, a partir de todos sus hallazgos.
 *
 * Manda la clase más grave: un solo hallazgo fatal rechaza la copia, uno reparable pide
 * confirmar y recalcular, y los avisos solo se cuentan. Los avisos nunca cambian la conclusión.
 */
sealed interface CandidateVerdict {
    /** Los hallazgos de clase aviso, que se cuentan y se dicen al confirmar. */
    val warnings: List<CandidateFinding>

    /**
     * Las marcas que lleva en sus metadatos una copia recién creada con esta conclusión: si cuenta
     * como válida y si es fatal.
     */
    val newCopyMarks: NewCopyMarks

    /** Nada que rechazar ni recalcular. Puede tener avisos. */
    data class Restorable(override val warnings: List<CandidateFinding>) : CandidateVerdict {
        override val newCopyMarks: NewCopyMarks get() = NewCopyMarks(valid = true, fatal = false)
    }

    /**
     * Se restaura tras confirmar, recalculando los saldos de cabecera desde el libro y las líneas.
     * Al crearla, la copia se guarda igual, porque una copia dudosa vale más que ninguna, pero no
     * cuenta para podar.
     */
    data class NeedsRepair(
        val repairs: List<CandidateFinding>,
        override val warnings: List<CandidateFinding>,
    ) : CandidateVerdict {
        override val newCopyMarks: NewCopyMarks get() = NewCopyMarks(valid = false, fatal = false)
    }

    /**
     * La app no puede restaurar esta base. Al crearla, la copia se guarda solo para un rescate
     * manual, y los cambios siguen sin respaldar.
     */
    data class Rejected(
        val fatal: List<CandidateFinding>,
        val repairs: List<CandidateFinding>,
        override val warnings: List<CandidateFinding>,
    ) : CandidateVerdict {
        override val newCopyMarks: NewCopyMarks get() = NewCopyMarks(valid = false, fatal = true)
    }

    companion object {
        /**
         * La conclusión que corresponde a [findings], separados por clase en el orden en que
         * llegaron.
         */
        fun of(findings: List<CandidateFinding>): CandidateVerdict {
            val bySeverity = findings.groupBy(CandidateFinding::severity)
            val fatal = bySeverity[FindingSeverity.FATAL].orEmpty()
            val repairs = bySeverity[FindingSeverity.REPAIRABLE].orEmpty()
            val warnings = bySeverity[FindingSeverity.WARNING].orEmpty()
            return when {
                fatal.isNotEmpty() -> Rejected(fatal, repairs, warnings)
                repairs.isNotEmpty() -> NeedsRepair(repairs, warnings)
                else -> Restorable(warnings)
            }
        }
    }
}

/**
 * Las marcas del validador en los metadatos de una copia nueva.
 *
 * [valid] es `false` con cualquier hallazgo fatal o reparable: esa copia no cuenta para podar.
 * [fatal] es `true` solo con un hallazgo fatal: la app no restaura esa copia, y los cambios siguen
 * sin respaldar. Un aviso no cambia ninguna de las dos.
 */
data class NewCopyMarks(val valid: Boolean, val fatal: Boolean)
