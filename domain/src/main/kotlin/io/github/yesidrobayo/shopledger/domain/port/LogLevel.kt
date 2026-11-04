package io.github.yesidrobayo.shopledger.domain.port

/**
 * La gravedad de una línea del registro.
 *
 * En la app publicada solo quedan [WARN] y [ERROR]: la compilación de release borra las líneas de
 * [INFO], que sirven para seguir la app mientras se construye.
 */
enum class LogLevel {
    INFO,
    WARN,
    ERROR,
}
