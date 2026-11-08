package io.github.yesidrobayo.shopledger.domain.model.experience

/** Los grupos del centro `Negocio`, en el orden en que se muestran. */
enum class ToolGroup {
    MONEY,
    SALES_AND_DOCUMENTS,
    PRICES,
    CATALOG_AND_INVENTORY,
    REPORTS,
}

/**
 * Cada herramienta del centro `Negocio`, con su grupo, en el orden en que se muestran dentro de él.
 *
 * Son todas las que tendrá la app, aunque su pantalla todavía no exista: así saber si una está en
 * uso no depende de que su pantalla esté hecha, y una copia que trae datos de una herramienta los
 * muestra en cuanto su pantalla llega. Qué herramientas se ofrecen lo decide la interfaz.
 *
 * Los nombres son claves internas y no cambian.
 */
enum class BusinessTool(val group: ToolGroup) {
    CUSTOMERS(ToolGroup.MONEY),
    LOYALTY(ToolGroup.MONEY),
    MONEY_ACCOUNTS(ToolGroup.MONEY),
    PAYABLES(ToolGroup.MONEY),
    CASH_REGISTER(ToolGroup.MONEY),
    HELD_SALES(ToolGroup.SALES_AND_DOCUMENTS),
    QUOTES(ToolGroup.SALES_AND_DOCUMENTS),
    ORDERS(ToolGroup.SALES_AND_DOCUMENTS),
    PENDING_FISCAL(ToolGroup.SALES_AND_DOCUMENTS),
    SHAREABLE_CATALOG(ToolGroup.SALES_AND_DOCUMENTS),
    LABELS(ToolGroup.SALES_AND_DOCUMENTS),
    PRICE_LISTS(ToolGroup.PRICES),
    REFERENCE_CURRENCY(ToolGroup.PRICES),
    UNITS(ToolGroup.CATALOG_AND_INVENTORY),
    COMBOS(ToolGroup.CATALOG_AND_INVENTORY),
    CUSTOM_FIELDS(ToolGroup.CATALOG_AND_INVENTORY),
    LOTS(ToolGroup.CATALOG_AND_INVENTORY),
    LOCATIONS(ToolGroup.CATALOG_AND_INVENTORY),
    STOCK_COUNT(ToolGroup.CATALOG_AND_INVENTORY),
    PRODUCTION(ToolGroup.CATALOG_AND_INVENTORY),
    REPLENISHMENT(ToolGroup.CATALOG_AND_INVENTORY),
    SUPPLIER_RETURNS(ToolGroup.CATALOG_AND_INVENTORY),
    IMPORT_DATA(ToolGroup.CATALOG_AND_INVENTORY),
    MANAGEMENT_SUMMARY(ToolGroup.REPORTS),
    GOALS_AND_LIMITS(ToolGroup.REPORTS),
    RECURRING_EXPENSES(ToolGroup.REPORTS),
    PRICE_INDEX(ToolGroup.REPORTS),
}
