package io.github.yesidrobayo.shopledger.data.local.expense

import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

// Apoyo de las pruebas de gastos. Todos los nombres, claves y montos son inventados.

/** Una categoría que creó la persona: con nombre, sin clave inicial, vigente. */
internal fun expenseCategory(
    name: String,
    isFixed: Long = 0,
    archivedAt: Long? = null,
): ExpenseCategoryEntity = ExpenseCategoryEntity(
    systemKey = null,
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    isSystem = 0,
    isFixed = isFixed,
    archivedAt = archivedAt,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/**
 * Una categoría inicial: con su clave y sin nombre, que se muestra con el texto traducido de la
 * clave. [isSystem] solo vale 1 en la de otros gastos.
 */
internal fun seededExpenseCategory(systemKey: String, isSystem: Long = 0): ExpenseCategoryEntity =
    ExpenseCategoryEntity(
        systemKey = systemKey,
        name = null,
        normalizedName = null,
        isSystem = isSystem,
        isFixed = 0,
        archivedAt = null,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

/** Un gasto activo de 45.000 en unidad menor, sin cuenta de dinero ni gasto recurrente. */
internal fun expense(
    categoryId: Long,
    businessDate: String = "2026-01-01",
    occurredAt: Long = CREATED_AT,
): ExpenseEntity = ExpenseEntity(
    expenseCategoryId = categoryId,
    status = "ACTIVE",
    amount = 45_000,
    description = null,
    moneyAccountId = null,
    recurringExpenseId = null,
    occurredAt = occurredAt,
    createdAt = occurredAt,
    businessDate = businessDate,
    voidedAt = null,
    voidReason = null,
    voidNote = null,
)
