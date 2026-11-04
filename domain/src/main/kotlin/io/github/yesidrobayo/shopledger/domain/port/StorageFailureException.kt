package io.github.yesidrobayo.shopledger.domain.port

/**
 * La base no pudo escribir, por [failure]. La lanza [TransactionRunner], y el caso de uso la
 * convierte en su propio resultado.
 *
 * No lleva mensaje ni causa con texto: el texto de una excepción de la base puede traer SQL o datos
 * del negocio, y nada de eso debe llegar a un registro.
 */
class StorageFailureException(val failure: StorageFailure) : RuntimeException()
