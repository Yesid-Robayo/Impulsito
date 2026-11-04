package io.github.yesidrobayo.shopledger.data

/**
 * Lo que dio [DataGraph.open]: los puertos, o una base que no abrió.
 *
 * La compuerta del arranque, que todavía no existe, agregará aquí sus estados: la pantalla de
 * rescate cuando la base debería existir y falta, o no abre, y la espera mientras se copia y se
 * migra. Por eso es un resultado cerrado y no una excepción: quien lo recibe decide qué mostrar
 * con un `when`, y el compilador le avisa cuando aparezca un estado nuevo.
 */
sealed interface DataAccess {
    /** La base abrió, y estos son sus puertos. */
    class Ready(val ports: DataPorts) : DataAccess

    /**
     * La base no abrió, por ejemplo porque viene de una versión más nueva de la app o está dañada.
     * La causa quedó registrada por su clase. El archivo queda como estaba: nada lo borra ni lo
     * reemplaza.
     */
    data object Unavailable : DataAccess
}
