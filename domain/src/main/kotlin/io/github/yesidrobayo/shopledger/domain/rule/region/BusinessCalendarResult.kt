package io.github.yesidrobayo.shopledger.domain.rule.region

/** Resultado de [BusinessCalendar.of]. */
sealed interface BusinessCalendarResult {
    /** La zona guardada existe en este teléfono: [calendar] fecha registros y arma períodos. */
    data class Ready(val calendar: BusinessCalendar) : BusinessCalendarResult

    /**
     * La base de zonas de este teléfono no trae [storedZoneId]. Puede llegar en una copia hecha en
     * un teléfono con zonas más nuevas, porque al restaurar solo se revisa su forma.
     *
     * La app abre igual y lo guardado conserva su fecha, pero no se registra nada con fecha hasta
     * que la persona elija una zona que este teléfono conozca.
     */
    data class UnknownZone(val storedZoneId: String) : BusinessCalendarResult
}
