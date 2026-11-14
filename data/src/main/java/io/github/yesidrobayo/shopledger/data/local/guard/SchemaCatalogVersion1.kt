// Generado por la tarea generateSchemaCatalog de :data, desde el JSON que Room exporta
// de esta versión y desde SchemaGuards. No se edita a mano. Cuando la versión del
// esquema sube, este archivo queda congelado, igual que su JSON: sus guardas ya no
// viven en SchemaGuards.
package io.github.yesidrobayo.shopledger.data.local.guard

/**
 * El esquema de la versión 1: 65 tablas y 162 guardas.
 */
internal fun schemaCatalogVersion1(): SchemaVersionCatalog = SchemaVersionCatalog(
    version = 1,
    identityHash = "54d9ab688a172d05f6dc4f3a802f73ca",
    tables =
        listOf(
            appSettingsTable(),
            auditEventsTable(),
            backupStateTable(),
            businessGoalsTable(),
            cashDenominationsTable(),
            cashSessionCountsTable(),
            cashSessionsTable(),
            categoriesTable(),
            countSessionLinesTable(),
            countSessionsTable(),
            customFieldDefinitionsTable(),
            customerLedgerEntriesTable(),
            customersTable(),
            documentAttachmentsTable(),
            expenseCategoriesTable(),
            expensesTable(),
            fxRatesTable(),
            locationsTable(),
            lowStockAlertsTable(),
            loyaltyLedgerEntriesTable(),
            mediaAssetsTable(),
            messageTemplatesTable(),
            moneyAccountsTable(),
            moneyMovementsTable(),
            orderLinesTable(),
            ordersTable(),
            paymentMethodsTable(),
            priceIndexEntriesTable(),
            priceListsTable(),
            productComponentsTable(),
            productCustomValuesTable(),
            productModifiersTable(),
            productPriceHistoryTable(),
            productPricesTable(),
            productTagsTable(),
            productUnitsTable(),
            productionBatchLinesTable(),
            productionBatchesTable(),
            productsTable(),
            purchaseLinesTable(),
            purchaseReturnLinesTable(),
            purchaseReturnsTable(),
            purchasesTable(),
            quoteLinesTable(),
            quotesTable(),
            recurringExpensesTable(),
            saleDraftLinesTable(),
            saleDraftsTable(),
            saleFiscalMarksTable(),
            saleLineComponentsTable(),
            saleLinesTable(),
            salePaymentsTable(),
            saleReturnLinesTable(),
            saleReturnsTable(),
            salesTable(),
            stockBalancesTable(),
            stockLotsTable(),
            stockMovementsTable(),
            stockTransferLinesTable(),
            stockTransfersTable(),
            supplierLedgerEntriesTable(),
            suppliersTable(),
            tagsTable(),
            taxRatesTable(),
            unitsTable(),
        ),
    triggers = triggers(),
)

private fun appSettingsTable(): CatalogTable = CatalogTable(
    name = "app_settings",
    autoIncrement = false,
    columns =
        listOf(
            column("app_lock_enabled", "INTEGER", notNull = true),
            column("app_lock_timeout_seconds", "INTEGER", notNull = true),
            column("business_name", "TEXT", notNull = false),
            column("business_time_zone", "TEXT", notNull = true),
            column("cash_rounding", "INTEGER", notNull = true),
            column("color_palette", "TEXT", notNull = true),
            column("cost_stale_days", "INTEGER", notNull = true),
            column("csv_separator", "TEXT", notNull = true),
            column("currency_code", "TEXT", notNull = true),
            column("currency_decimals", "INTEGER", notNull = true),
            column("currency_locked_at", "INTEGER", notNull = false),
            column("currency_symbol", "TEXT", notNull = false),
            column("daily_summary_time", "TEXT", notNull = false),
            column("default_rounding_step", "INTEGER", notNull = true),
            column("default_target_margin_bp", "INTEGER", notNull = true),
            column("default_tax_rate_id", "INTEGER", notNull = false),
            column("discreet_mode_enabled", "INTEGER", notNull = true),
            column("expiry_notice_days", "INTEGER", notNull = true),
            column("fiscal_portal_url", "TEXT", notNull = false),
            column("format_locale", "TEXT", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("low_stock_notifications_enabled", "INTEGER", notNull = true),
            column("loyalty_amount_per_point", "INTEGER", notNull = false),
            column("loyalty_enabled", "INTEGER", notNull = true),
            column("loyalty_point_value", "INTEGER", notNull = false),
            column("media_on_metered", "INTEGER", notNull = true),
            column("metered_backup_policy", "TEXT", notNull = true),
            column("onboarding_completed_at", "INTEGER", notNull = false),
            column("purchase_tax_recoverable", "INTEGER", notNull = true),
            column("receipt_business_details", "TEXT", notNull = false),
            column("receipt_footer_text", "TEXT", notNull = false),
            column("receipt_logo_media_id", "INTEGER", notNull = false),
            column("reference_currency_code", "TEXT", notNull = false),
            column("reference_currency_decimals", "INTEGER", notNull = false),
            column("revenue_limit_amount", "INTEGER", notNull = false),
            column("revenue_limit_months", "INTEGER", notNull = false),
            column("tax_mode", "TEXT", notNull = true),
            column("theme_mode", "TEXT", notNull = true),
            column("tips_enabled", "INTEGER", notNull = true),
            column("update_check_enabled", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
            column("week_start_day", "INTEGER", notNull = true),
        ),
    indices = emptyList(),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("default_tax_rate_id"),
                referencedTable = "tax_rates",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("receipt_logo_media_id"),
                referencedTable = "media_assets",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
        ),
)

private fun auditEventsTable(): CatalogTable = CatalogTable(
    name = "audit_events",
    autoIncrement = true,
    columns =
        listOf(
            column("event_type", "TEXT", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("occurred_at", "INTEGER", notNull = true),
            column("result", "TEXT", notNull = true),
        ),
    indices = emptyList(),
    foreignKeys = emptyList(),
)

private fun backupStateTable(): CatalogTable = CatalogTable(
    name = "backup_state",
    autoIncrement = false,
    columns =
        listOf(
            column("change_seq", "INTEGER", notNull = true),
            column("conflict_device_id", "TEXT", notNull = false),
            column("conflict_file_ref", "TEXT", notNull = false),
            column("conflict_generation", "INTEGER", notNull = false),
            column("conflict_kind", "TEXT", notNull = false),
            column("consecutive_failures", "INTEGER", notNull = true),
            column("copy_created_at", "INTEGER", notNull = false),
            column("copy_device_id", "TEXT", notNull = false),
            column("copy_generation", "INTEGER", notNull = false),
            column("destination_account", "TEXT", notNull = false),
            column("destination_folder", "TEXT", notNull = false),
            column("destination_id", "TEXT", notNull = false),
            column("destination_kind", "TEXT", notNull = false),
            column("failure_code", "TEXT", notNull = false),
            column("first_unsynced_at", "INTEGER", notNull = false),
            column("head_device_id", "TEXT", notNull = false),
            column("head_file_ref", "TEXT", notNull = false),
            column("head_generation", "INTEGER", notNull = true),
            column("human_check_generation", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("invariant_failures", "INTEGER", notNull = true),
            column("last_attempt_at", "INTEGER", notNull = false),
            column("last_drill_at", "INTEGER", notNull = false),
            column("last_drill_result", "TEXT", notNull = false),
            column("last_failure_kind", "TEXT", notNull = false),
            column("last_full_verify_at", "INTEGER", notNull = false),
            column("last_human_check_at", "INTEGER", notNull = false),
            column("last_stale_notice_at", "INTEGER", notNull = false),
            column("last_success_at", "INTEGER", notNull = false),
            column("last_upload_finished_at", "INTEGER", notNull = false),
            column("link_state", "TEXT", notNull = true),
            column("pending_generation", "INTEGER", notNull = false),
            column("pending_seq", "INTEGER", notNull = false),
            column("pending_sha256", "TEXT", notNull = false),
            column("pending_size_bytes", "INTEGER", notNull = false),
            column("shrink_accepted_generation", "INTEGER", notNull = false),
            column("verified_seq", "INTEGER", notNull = true),
        ),
    indices = emptyList(),
    foreignKeys = emptyList(),
)

private fun businessGoalsTable(): CatalogTable = CatalogTable(
    name = "business_goals",
    autoIncrement = true,
    columns =
        listOf(
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("month", "TEXT", notNull = true),
            column("profit_goal", "INTEGER", notNull = false),
            column("sales_goal", "INTEGER", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex("index_business_goals_month", key("month")),
        ),
    foreignKeys = emptyList(),
)

private fun cashDenominationsTable(): CatalogTable = CatalogTable(
    name = "cash_denominations",
    autoIncrement = true,
    columns =
        listOf(
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("is_active", "INTEGER", notNull = true),
            column("sort_order", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
            column("value", "INTEGER", notNull = true),
        ),
    indices = emptyList(),
    foreignKeys = emptyList(),
)

private fun cashSessionCountsTable(): CatalogTable = CatalogTable(
    name = "cash_session_counts",
    autoIncrement = true,
    columns =
        listOf(
            column("cash_session_id", "INTEGER", notNull = true),
            column("count", "INTEGER", notNull = true),
            column("denomination_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("phase", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex(
                "index_cash_session_counts_cash_session_id_phase_denomination_id",
                key("cash_session_id"),
                key("phase"),
                key("denomination_id"),
            ),
            index("index_cash_session_counts_denomination_id", key("denomination_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("cash_session_id"),
                referencedTable = "cash_sessions",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("denomination_id"),
                referencedTable = "cash_denominations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun cashSessionsTable(): CatalogTable = CatalogTable(
    name = "cash_sessions",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("closed_at", "INTEGER", notNull = false),
            column("counted_amount", "INTEGER", notNull = false),
            column("difference_amount", "INTEGER", notNull = false),
            column("expected_amount", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("money_account_id", "INTEGER", notNull = true),
            column("note", "TEXT", notNull = false),
            column("opened_at", "INTEGER", notNull = true),
            column("opening_amount", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            index(
                "index_cash_sessions_money_account_id_status",
                key("money_account_id"),
                key("status"),
            ),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun categoriesTable(): CatalogTable = CatalogTable(
    name = "categories",
    autoIncrement = true,
    columns =
        listOf(
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("name", "TEXT", notNull = true),
            column("normalized_name", "TEXT", notNull = true),
            column("target_margin_bp", "INTEGER", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex("index_categories_normalized_name", key("normalized_name")),
        ),
    foreignKeys = emptyList(),
)

private fun countSessionLinesTable(): CatalogTable = CatalogTable(
    name = "count_session_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("count_session_id", "INTEGER", notNull = true),
            column("counted_milli", "INTEGER", notNull = false),
            column("expected_milli", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("lot_id", "INTEGER", notNull = false),
            column("product_id", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_count_session_lines_count_session_id", key("count_session_id")),
            index("index_count_session_lines_product_id", key("product_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("count_session_id"),
                referencedTable = "count_sessions",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("lot_id"),
                referencedTable = "stock_lots",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun countSessionsTable(): CatalogTable = CatalogTable(
    name = "count_sessions",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("category_id", "INTEGER", notNull = false),
            column("finished_at", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("is_blind", "INTEGER", notNull = true),
            column("location_id", "INTEGER", notNull = true),
            column("note", "TEXT", notNull = false),
            column("started_at", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            index("index_count_sessions_category_id", key("category_id")),
            index("index_count_sessions_status", key("status")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("category_id"),
                referencedTable = "categories",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun customFieldDefinitionsTable(): CatalogTable = CatalogTable(
    name = "custom_field_definitions",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("field_type", "TEXT", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("name", "TEXT", notNull = true),
            column("normalized_name", "TEXT", notNull = true),
            column("sort_order", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_custom_field_definitions_normalized_name", key("normalized_name")),
        ),
    foreignKeys = emptyList(),
)

private fun customerLedgerEntriesTable(): CatalogTable = CatalogTable(
    name = "customer_ledger_entries",
    autoIncrement = true,
    columns =
        listOf(
            column("amount", "INTEGER", notNull = true),
            column("balance_after", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("customer_id", "INTEGER", notNull = true),
            column("due_date", "TEXT", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("reverses_entry_id", "INTEGER", notNull = false),
            column("sale_id", "INTEGER", notNull = false),
            column("sale_return_id", "INTEGER", notNull = false),
            column("type", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            index("index_customer_ledger_entries_customer_id", key("customer_id")),
            index("index_customer_ledger_entries_due_date", key("due_date")),
            uniqueIndex(
                "index_customer_ledger_entries_reverses_entry_id",
                key("reverses_entry_id"),
            ),
            index("index_customer_ledger_entries_sale_id", key("sale_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("customer_id"),
                referencedTable = "customers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("reverses_entry_id"),
                referencedTable = "customer_ledger_entries",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_return_id"),
                referencedTable = "sale_returns",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun customersTable(): CatalogTable = CatalogTable(
    name = "customers",
    autoIncrement = true,
    columns =
        listOf(
            column("anonymized_at", "INTEGER", notNull = false),
            column("archived_at", "INTEGER", notNull = false),
            column("balance", "INTEGER", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("email", "TEXT", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("name", "TEXT", notNull = false),
            column("normalized_name", "TEXT", notNull = false),
            column("notes", "TEXT", notNull = false),
            column("phone", "TEXT", notNull = false),
            column("points_balance", "INTEGER", notNull = true),
            column("price_list_id", "INTEGER", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_customers_normalized_name", key("normalized_name")),
            index("index_customers_price_list_id", key("price_list_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("price_list_id"),
                referencedTable = "price_lists",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
        ),
)

private fun documentAttachmentsTable(): CatalogTable = CatalogTable(
    name = "document_attachments",
    autoIncrement = true,
    columns =
        listOf(
            column("caption", "TEXT", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("expense_id", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("media_asset_id", "INTEGER", notNull = true),
            column("purchase_id", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_document_attachments_expense_id", key("expense_id")),
            index("index_document_attachments_media_asset_id", key("media_asset_id")),
            index("index_document_attachments_purchase_id", key("purchase_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("expense_id"),
                referencedTable = "expenses",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("media_asset_id"),
                referencedTable = "media_assets",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_id"),
                referencedTable = "purchases",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun expenseCategoriesTable(): CatalogTable = CatalogTable(
    name = "expense_categories",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("is_fixed", "INTEGER", notNull = true),
            column("is_system", "INTEGER", notNull = true),
            column("name", "TEXT", notNull = false),
            column("normalized_name", "TEXT", notNull = false),
            column("system_key", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_expense_categories_normalized_name", key("normalized_name")),
            uniqueIndex("index_expense_categories_system_key", key("system_key")),
        ),
    foreignKeys = emptyList(),
)

private fun expensesTable(): CatalogTable = CatalogTable(
    name = "expenses",
    autoIncrement = true,
    columns =
        listOf(
            column("amount", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("description", "TEXT", notNull = false),
            column("expense_category_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("money_account_id", "INTEGER", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("recurring_expense_id", "INTEGER", notNull = false),
            column("status", "TEXT", notNull = true),
            column("void_note", "TEXT", notNull = false),
            column("void_reason", "TEXT", notNull = false),
            column("voided_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_expenses_expense_category_id", key("expense_category_id")),
            index("index_expenses_status_business_date", key("status"), key("business_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("expense_category_id"),
                referencedTable = "expense_categories",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("recurring_expense_id"),
                referencedTable = "recurring_expenses",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun fxRatesTable(): CatalogTable = CatalogTable(
    name = "fx_rates",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("currency_code", "TEXT", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("rate_micro", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index(
                "index_fx_rates_currency_code_occurred_at",
                key("currency_code"),
                key("occurred_at"),
            ),
        ),
    foreignKeys = emptyList(),
)

private fun locationsTable(): CatalogTable = CatalogTable(
    name = "locations",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("kind", "TEXT", notNull = true),
            column("name", "TEXT", notNull = false),
            column("normalized_name", "TEXT", notNull = false),
            column("system_key", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_locations_normalized_name", key("normalized_name")),
            uniqueIndex("index_locations_system_key", key("system_key")),
        ),
    foreignKeys = emptyList(),
)

private fun lowStockAlertsTable(): CatalogTable = CatalogTable(
    name = "low_stock_alerts",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("min_stock_milli_at_trigger", "INTEGER", notNull = true),
            column("notification_status", "TEXT", notNull = true),
            column("occurred_at", "INTEGER", notNull = true),
            column("on_hand_milli_at_trigger", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("resolved_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_low_stock_alerts_notification_status", key("notification_status")),
            index(
                "index_low_stock_alerts_product_id_resolved_at",
                key("product_id"),
                key("resolved_at"),
            ),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
        ),
)

private fun loyaltyLedgerEntriesTable(): CatalogTable = CatalogTable(
    name = "loyalty_ledger_entries",
    autoIncrement = true,
    columns =
        listOf(
            column("balance_after", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("customer_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("points", "INTEGER", notNull = true),
            column("reverses_entry_id", "INTEGER", notNull = false),
            column("sale_id", "INTEGER", notNull = false),
            column("type", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            index("index_loyalty_ledger_entries_customer_id", key("customer_id")),
            uniqueIndex("index_loyalty_ledger_entries_reverses_entry_id", key("reverses_entry_id")),
            index("index_loyalty_ledger_entries_sale_id", key("sale_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("customer_id"),
                referencedTable = "customers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("reverses_entry_id"),
                referencedTable = "loyalty_ledger_entries",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun mediaAssetsTable(): CatalogTable = CatalogTable(
    name = "media_assets",
    autoIncrement = true,
    columns =
        listOf(
            column("created_at", "INTEGER", notNull = true),
            column("height_px", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("kind", "TEXT", notNull = true),
            column("mime_type", "TEXT", notNull = true),
            column("orphaned_at", "INTEGER", notNull = false),
            column("sha256", "TEXT", notNull = true),
            column("size_bytes", "INTEGER", notNull = true),
            column("width_px", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_media_assets_orphaned_at", key("orphaned_at")),
            uniqueIndex("index_media_assets_sha256", key("sha256")),
        ),
    foreignKeys = emptyList(),
)

private fun messageTemplatesTable(): CatalogTable = CatalogTable(
    name = "message_templates",
    autoIncrement = true,
    columns =
        listOf(
            column("body", "TEXT", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("language_tag", "TEXT", notNull = true),
            column("template_key", "TEXT", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex(
                "index_message_templates_template_key_language_tag",
                key("template_key"),
                key("language_tag"),
            ),
        ),
    foreignKeys = emptyList(),
)

private fun moneyAccountsTable(): CatalogTable = CatalogTable(
    name = "money_accounts",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("balance", "INTEGER", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("currency_code", "TEXT", notNull = true),
            column("currency_decimals", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("kind", "TEXT", notNull = true),
            column("name", "TEXT", notNull = false),
            column("normalized_name", "TEXT", notNull = false),
            column("system_key", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_money_accounts_normalized_name", key("normalized_name")),
            uniqueIndex("index_money_accounts_system_key", key("system_key")),
        ),
    foreignKeys = emptyList(),
)

private fun moneyMovementsTable(): CatalogTable = CatalogTable(
    name = "money_movements",
    autoIncrement = true,
    columns =
        listOf(
            column("amount", "INTEGER", notNull = true),
            column("available_date", "TEXT", notNull = false),
            column("balance_after", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("cash_session_id", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("customer_ledger_entry_id", "INTEGER", notNull = false),
            column("expense_id", "INTEGER", notNull = false),
            column("fx_rate_micro", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("money_account_id", "INTEGER", notNull = true),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("purchase_id", "INTEGER", notNull = false),
            column("purchase_return_id", "INTEGER", notNull = false),
            column("reverses_movement_id", "INTEGER", notNull = false),
            column("sale_payment_id", "INTEGER", notNull = false),
            column("sale_return_id", "INTEGER", notNull = false),
            column("supplier_ledger_entry_id", "INTEGER", notNull = false),
            column("transfer_group_id", "TEXT", notNull = false),
            column("type", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            index("index_money_movements_cash_session_id", key("cash_session_id")),
            index("index_money_movements_money_account_id", key("money_account_id")),
            uniqueIndex("index_money_movements_reverses_movement_id", key("reverses_movement_id")),
            index("index_money_movements_sale_payment_id", key("sale_payment_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("cash_session_id"),
                referencedTable = "cash_sessions",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("customer_ledger_entry_id"),
                referencedTable = "customer_ledger_entries",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("expense_id"),
                referencedTable = "expenses",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_id"),
                referencedTable = "purchases",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_return_id"),
                referencedTable = "purchase_returns",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("reverses_movement_id"),
                referencedTable = "money_movements",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_payment_id"),
                referencedTable = "sale_payments",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_return_id"),
                referencedTable = "sale_returns",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("supplier_ledger_entry_id"),
                referencedTable = "supplier_ledger_entries",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun orderLinesTable(): CatalogTable = CatalogTable(
    name = "order_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("entered_quantity_milli", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_discount", "INTEGER", notNull = true),
            column("line_total", "INTEGER", notNull = true),
            column("net_amount", "INTEGER", notNull = true),
            column("order_id", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("product_name_snapshot", "TEXT", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("tax_amount", "INTEGER", notNull = true),
            column("tax_name_snapshot", "TEXT", notNull = false),
            column("tax_rate_bp", "INTEGER", notNull = true),
            column("unit_factor_milli", "INTEGER", notNull = true),
            column("unit_id", "INTEGER", notNull = true),
            column("unit_price", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_order_lines_order_id", key("order_id")),
            index("index_order_lines_product_id", key("product_id")),
            index("index_order_lines_unit_id", key("unit_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("order_id"),
                referencedTable = "orders",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("unit_id"),
                referencedTable = "units",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun ordersTable(): CatalogTable = CatalogTable(
    name = "orders",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("customer_id", "INTEGER", notNull = false),
            column("delivered_sale_id", "INTEGER", notNull = false),
            column("delivery_address", "TEXT", notNull = false),
            column("delivery_note", "TEXT", notNull = false),
            column("due_date", "TEXT", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("occurred_at", "INTEGER", notNull = true),
            column("shipping_amount", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
            column("total", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_orders_customer_id", key("customer_id")),
            index("index_orders_delivered_sale_id", key("delivered_sale_id")),
            index("index_orders_status_business_date", key("status"), key("business_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("customer_id"),
                referencedTable = "customers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("delivered_sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
        ),
)

private fun paymentMethodsTable(): CatalogTable = CatalogTable(
    name = "payment_methods",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("fee_bp", "INTEGER", notNull = true),
            column("fee_fixed", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("instructions_media_id", "INTEGER", notNull = false),
            column("instructions_text", "TEXT", notNull = false),
            column("kind", "TEXT", notNull = true),
            column("money_account_id", "INTEGER", notNull = false),
            column("name", "TEXT", notNull = false),
            column("normalized_name", "TEXT", notNull = false),
            column("price_adjustment_bp", "INTEGER", notNull = true),
            column("settlement_days", "INTEGER", notNull = true),
            column("sort_order", "INTEGER", notNull = true),
            column("system_key", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_payment_methods_instructions_media_id", key("instructions_media_id")),
            index("index_payment_methods_money_account_id", key("money_account_id")),
            index("index_payment_methods_normalized_name", key("normalized_name")),
            uniqueIndex("index_payment_methods_system_key", key("system_key")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("instructions_media_id"),
                referencedTable = "media_assets",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun priceIndexEntriesTable(): CatalogTable = CatalogTable(
    name = "price_index_entries",
    autoIncrement = true,
    columns =
        listOf(
            column("change_bp", "INTEGER", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("month", "TEXT", notNull = true),
            column("source_note", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex("index_price_index_entries_month", key("month")),
        ),
    foreignKeys = emptyList(),
)

private fun priceListsTable(): CatalogTable = CatalogTable(
    name = "price_lists",
    autoIncrement = true,
    columns =
        listOf(
            column("adjustment_bp", "INTEGER", notNull = false),
            column("archived_at", "INTEGER", notNull = false),
            column("base_price_list_id", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("kind", "TEXT", notNull = true),
            column("name", "TEXT", notNull = false),
            column("normalized_name", "TEXT", notNull = false),
            column("system_key", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_price_lists_base_price_list_id", key("base_price_list_id")),
            index("index_price_lists_normalized_name", key("normalized_name")),
            uniqueIndex("index_price_lists_system_key", key("system_key")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("base_price_list_id"),
                referencedTable = "price_lists",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun productComponentsTable(): CatalogTable = CatalogTable(
    name = "product_components",
    autoIncrement = true,
    columns =
        listOf(
            column("component_product_id", "INTEGER", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("parent_product_id", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_product_components_component_product_id", key("component_product_id")),
            uniqueIndex(
                "index_product_components_parent_product_id_component_product_id",
                key("parent_product_id"),
                key("component_product_id"),
            ),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("component_product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("parent_product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
        ),
)

private fun productCustomValuesTable(): CatalogTable = CatalogTable(
    name = "product_custom_values",
    autoIncrement = true,
    columns =
        listOf(
            column("field_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("product_id", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
            column("value_text", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            index("index_product_custom_values_field_id", key("field_id")),
            uniqueIndex(
                "index_product_custom_values_product_id_field_id",
                key("product_id"),
                key("field_id"),
            ),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("field_id"),
                referencedTable = "custom_field_definitions",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
        ),
)

private fun productModifiersTable(): CatalogTable = CatalogTable(
    name = "product_modifiers",
    autoIncrement = true,
    columns =
        listOf(
            column("created_at", "INTEGER", notNull = true),
            column("group_name", "TEXT", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("modifier_product_id", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("sort_order", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_product_modifiers_modifier_product_id", key("modifier_product_id")),
            index("index_product_modifiers_product_id", key("product_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("modifier_product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
        ),
)

private fun productPriceHistoryTable(): CatalogTable = CatalogTable(
    name = "product_price_history",
    autoIncrement = true,
    columns =
        listOf(
            column("amount_delta", "INTEGER", notNull = false),
            column("batch_id", "TEXT", notNull = false),
            column("bulk_mode", "TEXT", notNull = false),
            column("business_date", "TEXT", notNull = true),
            column("fx_rate_micro", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("new_price", "INTEGER", notNull = true),
            column("occurred_at", "INTEGER", notNull = true),
            column("old_price", "INTEGER", notNull = false),
            column("percent_bp", "INTEGER", notNull = false),
            column("price_list_id", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("rounding_step", "INTEGER", notNull = false),
            column("source", "TEXT", notNull = true),
            column("target_margin_bp", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_product_price_history_batch_id", key("batch_id")),
            index("index_product_price_history_product_id", key("product_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("price_list_id"),
                referencedTable = "price_lists",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
        ),
)

private fun productPricesTable(): CatalogTable = CatalogTable(
    name = "product_prices",
    autoIncrement = true,
    columns =
        listOf(
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("price", "INTEGER", notNull = true),
            column("price_list_id", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex(
                "index_product_prices_price_list_id_product_id",
                key("price_list_id"),
                key("product_id"),
            ),
            index("index_product_prices_product_id", key("product_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("price_list_id"),
                referencedTable = "price_lists",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
        ),
)

private fun productTagsTable(): CatalogTable = CatalogTable(
    name = "product_tags",
    autoIncrement = true,
    columns =
        listOf(
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("product_id", "INTEGER", notNull = true),
            column("tag_id", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex("index_product_tags_product_id_tag_id", key("product_id"), key("tag_id")),
            index("index_product_tags_tag_id", key("tag_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("tag_id"),
                referencedTable = "tags",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
        ),
)

private fun productUnitsTable(): CatalogTable = CatalogTable(
    name = "product_units",
    autoIncrement = true,
    columns =
        listOf(
            column("barcode", "TEXT", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("factor_milli", "INTEGER", notNull = true),
            column("for_purchase", "INTEGER", notNull = true),
            column("for_sale", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("product_id", "INTEGER", notNull = true),
            column("sale_price", "INTEGER", notNull = false),
            column("unit_id", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_product_units_barcode", key("barcode")),
            uniqueIndex(
                "index_product_units_product_id_unit_id",
                key("product_id"),
                key("unit_id"),
            ),
            index("index_product_units_unit_id", key("unit_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("unit_id"),
                referencedTable = "units",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun productionBatchLinesTable(): CatalogTable = CatalogTable(
    name = "production_batch_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("component_product_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_cost", "INTEGER", notNull = true),
            column("lot_id", "INTEGER", notNull = false),
            column("production_batch_id", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("unit_cost", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_production_batch_lines_component_product_id", key("component_product_id")),
            index("index_production_batch_lines_production_batch_id", key("production_batch_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("component_product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("lot_id"),
                referencedTable = "stock_lots",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("production_batch_id"),
                referencedTable = "production_batches",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun productionBatchesTable(): CatalogTable = CatalogTable(
    name = "production_batches",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("location_id", "INTEGER", notNull = true),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
            column("total_cost", "INTEGER", notNull = true),
            column("void_note", "TEXT", notNull = false),
            column("void_reason", "TEXT", notNull = false),
            column("voided_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_production_batches_business_date", key("business_date")),
            index("index_production_batches_product_id", key("product_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun productsTable(): CatalogTable = CatalogTable(
    name = "products",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("average_cost", "INTEGER", notNull = true),
            column("barcode", "TEXT", notNull = false),
            column("base_unit_id", "INTEGER", notNull = true),
            column("category_id", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("default_supplier_id", "INTEGER", notNull = false),
            column("expiry_notice_days", "INTEGER", notNull = false),
            column("fixed_unit_cost", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("initial_cost", "INTEGER", notNull = true),
            column("min_stock_milli", "INTEGER", notNull = true),
            column("name", "TEXT", notNull = true),
            column("normalized_name", "TEXT", notNull = true),
            column("on_hand_milli", "INTEGER", notNull = true),
            column("parent_product_id", "INTEGER", notNull = false),
            column("photo_media_id", "INTEGER", notNull = false),
            column("price_review_since", "INTEGER", notNull = false),
            column("reference_cost", "INTEGER", notNull = false),
            column("reference_price", "INTEGER", notNull = false),
            column("replacement_cost", "INTEGER", notNull = true),
            column("replacement_cost_at", "INTEGER", notNull = false),
            column("sale_price", "INTEGER", notNull = true),
            column("stock_mode", "TEXT", notNull = true),
            column("stock_value", "INTEGER", notNull = true),
            column("target_margin_bp", "INTEGER", notNull = false),
            column("tax_rate_id", "INTEGER", notNull = false),
            column("tracks_lots", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
            column("usage", "TEXT", notNull = true),
            column("variant_axis_1", "TEXT", notNull = false),
            column("variant_axis_2", "TEXT", notNull = false),
            column("variant_value_1", "TEXT", notNull = false),
            column("variant_value_2", "TEXT", notNull = false),
        ),
    indices =
        listOf(
            index("index_products_barcode", key("barcode")),
            index("index_products_base_unit_id", key("base_unit_id")),
            index("index_products_category_id", key("category_id")),
            index("index_products_default_supplier_id", key("default_supplier_id")),
            index("index_products_normalized_name", key("normalized_name")),
            index("index_products_parent_product_id", key("parent_product_id")),
            index("index_products_photo_media_id", key("photo_media_id")),
            index("index_products_tax_rate_id", key("tax_rate_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("base_unit_id"),
                referencedTable = "units",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("category_id"),
                referencedTable = "categories",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("default_supplier_id"),
                referencedTable = "suppliers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("parent_product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("photo_media_id"),
                referencedTable = "media_assets",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("tax_rate_id"),
                referencedTable = "tax_rates",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
        ),
)

private fun purchaseLinesTable(): CatalogTable = CatalogTable(
    name = "purchase_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("cost_amount", "INTEGER", notNull = true),
            column("entered_quantity_milli", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_total", "INTEGER", notNull = true),
            column("lot_id", "INTEGER", notNull = false),
            column("product_id", "INTEGER", notNull = true),
            column("purchase_id", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("reference_unit_cost", "INTEGER", notNull = false),
            column("tax_amount", "INTEGER", notNull = true),
            column("tax_name_snapshot", "TEXT", notNull = false),
            column("tax_rate_bp", "INTEGER", notNull = true),
            column("unit_cost", "INTEGER", notNull = true),
            column("unit_factor_milli", "INTEGER", notNull = true),
            column("unit_id", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_purchase_lines_product_id", key("product_id")),
            index("index_purchase_lines_purchase_id", key("purchase_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("lot_id"),
                referencedTable = "stock_lots",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_id"),
                referencedTable = "purchases",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("unit_id"),
                referencedTable = "units",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun purchaseReturnLinesTable(): CatalogTable = CatalogTable(
    name = "purchase_return_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_total", "INTEGER", notNull = true),
            column("lot_id", "INTEGER", notNull = false),
            column("product_id", "INTEGER", notNull = true),
            column("purchase_line_id", "INTEGER", notNull = false),
            column("purchase_return_id", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("unit_cost", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_purchase_return_lines_product_id", key("product_id")),
            index("index_purchase_return_lines_purchase_return_id", key("purchase_return_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("lot_id"),
                referencedTable = "stock_lots",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_line_id"),
                referencedTable = "purchase_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_return_id"),
                referencedTable = "purchase_returns",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun purchaseReturnsTable(): CatalogTable = CatalogTable(
    name = "purchase_returns",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("money_account_id", "INTEGER", notNull = false),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("purchase_id", "INTEGER", notNull = false),
            column("resolution", "TEXT", notNull = true),
            column("status", "TEXT", notNull = true),
            column("supplier_id", "INTEGER", notNull = false),
            column("total", "INTEGER", notNull = true),
            column("void_note", "TEXT", notNull = false),
            column("void_reason", "TEXT", notNull = false),
            column("voided_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_purchase_returns_purchase_id", key("purchase_id")),
            index(
                "index_purchase_returns_status_business_date",
                key("status"),
                key("business_date"),
            ),
            index("index_purchase_returns_supplier_id", key("supplier_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_id"),
                referencedTable = "purchases",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("supplier_id"),
                referencedTable = "suppliers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun purchasesTable(): CatalogTable = CatalogTable(
    name = "purchases",
    autoIncrement = true,
    columns =
        listOf(
            column("amount_paid", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("cost_total", "INTEGER", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("due_date", "TEXT", notNull = false),
            column("fx_currency_code", "TEXT", notNull = false),
            column("fx_rate_micro", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("invoice_reference", "TEXT", notNull = false),
            column("location_id", "INTEGER", notNull = true),
            column("money_account_id", "INTEGER", notNull = false),
            column("net_total", "INTEGER", notNull = true),
            column("occurred_at", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
            column("supplier_id", "INTEGER", notNull = false),
            column("tax_total", "INTEGER", notNull = true),
            column("total", "INTEGER", notNull = true),
            column("void_note", "TEXT", notNull = false),
            column("void_reason", "TEXT", notNull = false),
            column("voided_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_purchases_due_date", key("due_date")),
            index("index_purchases_status_business_date", key("status"), key("business_date")),
            index("index_purchases_supplier_id", key("supplier_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("supplier_id"),
                referencedTable = "suppliers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun quoteLinesTable(): CatalogTable = CatalogTable(
    name = "quote_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("entered_quantity_milli", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_discount", "INTEGER", notNull = true),
            column("line_total", "INTEGER", notNull = true),
            column("net_amount", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("product_name_snapshot", "TEXT", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("quote_id", "INTEGER", notNull = true),
            column("tax_amount", "INTEGER", notNull = true),
            column("tax_name_snapshot", "TEXT", notNull = false),
            column("tax_rate_bp", "INTEGER", notNull = true),
            column("unit_factor_milli", "INTEGER", notNull = true),
            column("unit_id", "INTEGER", notNull = true),
            column("unit_price", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_quote_lines_product_id", key("product_id")),
            index("index_quote_lines_quote_id", key("quote_id")),
            index("index_quote_lines_unit_id", key("unit_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("quote_id"),
                referencedTable = "quotes",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("unit_id"),
                referencedTable = "units",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun quotesTable(): CatalogTable = CatalogTable(
    name = "quotes",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("converted_sale_id", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("customer_id", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("net_total", "INTEGER", notNull = true),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
            column("tax_total", "INTEGER", notNull = true),
            column("total", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
            column("valid_until", "TEXT", notNull = false),
        ),
    indices =
        listOf(
            index("index_quotes_converted_sale_id", key("converted_sale_id")),
            index("index_quotes_customer_id", key("customer_id")),
            index("index_quotes_status_business_date", key("status"), key("business_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("converted_sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("customer_id"),
                referencedTable = "customers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun recurringExpensesTable(): CatalogTable = CatalogTable(
    name = "recurring_expenses",
    autoIncrement = true,
    columns =
        listOf(
            column("amount", "INTEGER", notNull = true),
            column("archived_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("day_of_period", "INTEGER", notNull = true),
            column("description", "TEXT", notNull = false),
            column("expense_category_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("money_account_id", "INTEGER", notNull = false),
            column("next_due_date", "TEXT", notNull = true),
            column("recurrence", "TEXT", notNull = true),
            column("reminder_enabled", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_recurring_expenses_expense_category_id", key("expense_category_id")),
            index("index_recurring_expenses_next_due_date", key("next_due_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("expense_category_id"),
                referencedTable = "expense_categories",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun saleDraftLinesTable(): CatalogTable = CatalogTable(
    name = "sale_draft_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("draft_id", "INTEGER", notNull = true),
            column("entered_quantity_milli", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_discount", "INTEGER", notNull = true),
            column("parent_line_id", "INTEGER", notNull = false),
            column("product_id", "INTEGER", notNull = true),
            column("unit_id", "INTEGER", notNull = true),
            column("unit_price", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_sale_draft_lines_draft_id", key("draft_id")),
            index("index_sale_draft_lines_parent_line_id", key("parent_line_id")),
            index("index_sale_draft_lines_product_id", key("product_id")),
            index("index_sale_draft_lines_unit_id", key("unit_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("draft_id"),
                referencedTable = "sale_drafts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("parent_line_id"),
                referencedTable = "sale_draft_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "CASCADE",
            ),
            CatalogForeignKey(
                columns = listOf("unit_id"),
                referencedTable = "units",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun saleDraftsTable(): CatalogTable = CatalogTable(
    name = "sale_drafts",
    autoIncrement = true,
    columns =
        listOf(
            column("created_at", "INTEGER", notNull = true),
            column("customer_id", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("label", "TEXT", notNull = false),
            column("price_list_id", "INTEGER", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_sale_drafts_customer_id", key("customer_id")),
            index("index_sale_drafts_price_list_id", key("price_list_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("customer_id"),
                referencedTable = "customers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
            CatalogForeignKey(
                columns = listOf("price_list_id"),
                referencedTable = "price_lists",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
        ),
)

private fun saleFiscalMarksTable(): CatalogTable = CatalogTable(
    name = "sale_fiscal_marks",
    autoIncrement = true,
    columns =
        listOf(
            column("external_reference", "TEXT", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("recorded_at", "INTEGER", notNull = false),
            column("sale_id", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex("index_sale_fiscal_marks_sale_id", key("sale_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun saleLineComponentsTable(): CatalogTable = CatalogTable(
    name = "sale_line_components",
    autoIncrement = true,
    columns =
        listOf(
            column("component_product_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_cost", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("sale_line_id", "INTEGER", notNull = true),
            column("tracked", "INTEGER", notNull = true),
            column("unit_cost", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_sale_line_components_component_product_id", key("component_product_id")),
            index("index_sale_line_components_sale_line_id", key("sale_line_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("component_product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_line_id"),
                referencedTable = "sale_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun saleLinesTable(): CatalogTable = CatalogTable(
    name = "sale_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("entered_quantity_milli", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_cost", "INTEGER", notNull = true),
            column("line_discount", "INTEGER", notNull = true),
            column("line_replacement_cost", "INTEGER", notNull = true),
            column("line_total", "INTEGER", notNull = true),
            column("net_amount", "INTEGER", notNull = true),
            column("order_adjustment_share", "INTEGER", notNull = true),
            column("parent_line_id", "INTEGER", notNull = false),
            column("price_list_id", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("product_name_snapshot", "TEXT", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("sale_id", "INTEGER", notNull = true),
            column("stock_effect", "TEXT", notNull = true),
            column("tax_amount", "INTEGER", notNull = true),
            column("tax_name_snapshot", "TEXT", notNull = false),
            column("tax_rate_bp", "INTEGER", notNull = true),
            column("unit_cost", "INTEGER", notNull = true),
            column("unit_factor_milli", "INTEGER", notNull = true),
            column("unit_id", "INTEGER", notNull = true),
            column("unit_price", "INTEGER", notNull = true),
            column("unit_replacement_cost", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_sale_lines_product_id", key("product_id")),
            index("index_sale_lines_sale_id_product_id", key("sale_id"), key("product_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("parent_line_id"),
                referencedTable = "sale_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("price_list_id"),
                referencedTable = "price_lists",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("unit_id"),
                referencedTable = "units",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun salePaymentsTable(): CatalogTable = CatalogTable(
    name = "sale_payments",
    autoIncrement = true,
    columns =
        listOf(
            column("amount", "INTEGER", notNull = true),
            column("change_amount", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("fee_amount", "INTEGER", notNull = true),
            column("fx_rate_micro", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("method_kind_snapshot", "TEXT", notNull = true),
            column("method_name_snapshot", "TEXT", notNull = true),
            column("money_account_id", "INTEGER", notNull = false),
            column("payment_method_id", "INTEGER", notNull = true),
            column("sale_id", "INTEGER", notNull = true),
            column("settlement_date", "TEXT", notNull = true),
            column("tender_amount", "INTEGER", notNull = false),
            column("tender_currency_code", "TEXT", notNull = false),
            column("tender_currency_decimals", "INTEGER", notNull = false),
            column("tendered_amount", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index(
                "index_sale_payments_payment_method_id_sale_id",
                key("payment_method_id"),
                key("sale_id"),
            ),
            index("index_sale_payments_sale_id", key("sale_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("payment_method_id"),
                referencedTable = "payment_methods",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun saleReturnLinesTable(): CatalogTable = CatalogTable(
    name = "sale_return_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("line_cost", "INTEGER", notNull = true),
            column("line_total", "INTEGER", notNull = true),
            column("net_amount", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("restock", "INTEGER", notNull = true),
            column("sale_line_id", "INTEGER", notNull = true),
            column("sale_return_id", "INTEGER", notNull = true),
            column("tax_amount", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_sale_return_lines_sale_line_id", key("sale_line_id")),
            index("index_sale_return_lines_sale_return_id", key("sale_return_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("sale_line_id"),
                referencedTable = "sale_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_return_id"),
                referencedTable = "sale_returns",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun saleReturnsTable(): CatalogTable = CatalogTable(
    name = "sale_returns",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("cost_returned", "INTEGER", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("money_account_id", "INTEGER", notNull = false),
            column("net_refund", "INTEGER", notNull = true),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("replacement_sale_id", "INTEGER", notNull = false),
            column("resolution", "TEXT", notNull = true),
            column("sale_id", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
            column("tax_refund", "INTEGER", notNull = true),
            column("total_refund", "INTEGER", notNull = true),
            column("void_note", "TEXT", notNull = false),
            column("void_reason", "TEXT", notNull = false),
            column("voided_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_sale_returns_replacement_sale_id", key("replacement_sale_id")),
            index("index_sale_returns_sale_id", key("sale_id")),
            index("index_sale_returns_status_business_date", key("status"), key("business_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("money_account_id"),
                referencedTable = "money_accounts",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("replacement_sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_id"),
                referencedTable = "sales",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun salesTable(): CatalogTable = CatalogTable(
    name = "sales",
    autoIncrement = true,
    columns =
        listOf(
            column("amount_due", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("cash_rounding_amount", "INTEGER", notNull = true),
            column("cash_session_id", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("customer_id", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("location_id", "INTEGER", notNull = true),
            column("net_total", "INTEGER", notNull = true),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("order_adjustment", "INTEGER", notNull = true),
            column("order_id", "INTEGER", notNull = false),
            column("price_list_id", "INTEGER", notNull = true),
            column("quote_id", "INTEGER", notNull = false),
            column("status", "TEXT", notNull = true),
            column("tax_total", "INTEGER", notNull = true),
            column("tip_amount", "INTEGER", notNull = true),
            column("total", "INTEGER", notNull = true),
            column("total_cost", "INTEGER", notNull = true),
            column("total_replacement_cost", "INTEGER", notNull = true),
            column("void_note", "TEXT", notNull = false),
            column("void_reason", "TEXT", notNull = false),
            column("voided_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_sales_customer_id", key("customer_id")),
            index("index_sales_occurred_at_id", key("occurred_at"), key("id")),
            index("index_sales_status_business_date", key("status"), key("business_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("cash_session_id"),
                referencedTable = "cash_sessions",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("customer_id"),
                referencedTable = "customers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("order_id"),
                referencedTable = "orders",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("price_list_id"),
                referencedTable = "price_lists",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("quote_id"),
                referencedTable = "quotes",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun stockBalancesTable(): CatalogTable = CatalogTable(
    name = "stock_balances",
    autoIncrement = true,
    columns =
        listOf(
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("location_id", "INTEGER", notNull = true),
            column("lot_key", "INTEGER", notNull = true),
            column("on_hand_milli", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_stock_balances_location_id", key("location_id")),
            uniqueIndex(
                "index_stock_balances_product_id_location_id_lot_key",
                key("product_id"),
                key("location_id"),
                key("lot_key"),
            ),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun stockLotsTable(): CatalogTable = CatalogTable(
    name = "stock_lots",
    autoIncrement = true,
    columns =
        listOf(
            column("closed_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("expiry_date", "TEXT", notNull = false),
            column("expiry_notified_at", "INTEGER", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("is_unassigned", "INTEGER", notNull = true),
            column("lot_code", "TEXT", notNull = false),
            column("product_id", "INTEGER", notNull = true),
            column("purchase_line_id", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_stock_lots_expiry_date", key("expiry_date")),
            uniqueIndex("index_stock_lots_product_id_lot_code", key("product_id"), key("lot_code")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_line_id"),
                referencedTable = "purchase_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "SET NULL",
            ),
        ),
)

private fun stockMovementsTable(): CatalogTable = CatalogTable(
    name = "stock_movements",
    autoIncrement = true,
    columns =
        listOf(
            column("average_cost_after", "INTEGER", notNull = true),
            column("balance_after_milli", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("count_session_id", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("location_id", "INTEGER", notNull = true),
            column("lot_id", "INTEGER", notNull = false),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("product_id", "INTEGER", notNull = true),
            column("production_batch_id", "INTEGER", notNull = false),
            column("purchase_line_id", "INTEGER", notNull = false),
            column("purchase_return_line_id", "INTEGER", notNull = false),
            column("quantity_milli", "INTEGER", notNull = true),
            column("reason", "TEXT", notNull = false),
            column("reverses_movement_id", "INTEGER", notNull = false),
            column("sale_line_component_id", "INTEGER", notNull = false),
            column("sale_line_id", "INTEGER", notNull = false),
            column("sale_return_line_id", "INTEGER", notNull = false),
            column("stock_value_after", "INTEGER", notNull = true),
            column("total_cost", "INTEGER", notNull = true),
            column("transfer_line_id", "INTEGER", notNull = false),
            column("type", "TEXT", notNull = true),
            column("unit_cost", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_stock_movements_product_id", key("product_id")),
            index("index_stock_movements_purchase_line_id", key("purchase_line_id")),
            uniqueIndex("index_stock_movements_reverses_movement_id", key("reverses_movement_id")),
            index("index_stock_movements_sale_line_id", key("sale_line_id")),
            index("index_stock_movements_type_business_date", key("type"), key("business_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("count_session_id"),
                referencedTable = "count_sessions",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("lot_id"),
                referencedTable = "stock_lots",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("production_batch_id"),
                referencedTable = "production_batches",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_line_id"),
                referencedTable = "purchase_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_return_line_id"),
                referencedTable = "purchase_return_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("reverses_movement_id"),
                referencedTable = "stock_movements",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_line_component_id"),
                referencedTable = "sale_line_components",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_line_id"),
                referencedTable = "sale_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("sale_return_line_id"),
                referencedTable = "sale_return_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("transfer_line_id"),
                referencedTable = "stock_transfer_lines",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun stockTransferLinesTable(): CatalogTable = CatalogTable(
    name = "stock_transfer_lines",
    autoIncrement = true,
    columns =
        listOf(
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("lot_id", "INTEGER", notNull = false),
            column("product_id", "INTEGER", notNull = true),
            column("quantity_milli", "INTEGER", notNull = true),
            column("transfer_id", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_stock_transfer_lines_product_id", key("product_id")),
            index("index_stock_transfer_lines_transfer_id", key("transfer_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("lot_id"),
                referencedTable = "stock_lots",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("product_id"),
                referencedTable = "products",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("transfer_id"),
                referencedTable = "stock_transfers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun stockTransfersTable(): CatalogTable = CatalogTable(
    name = "stock_transfers",
    autoIncrement = true,
    columns =
        listOf(
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("from_location_id", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("status", "TEXT", notNull = true),
            column("to_location_id", "INTEGER", notNull = true),
            column("void_note", "TEXT", notNull = false),
            column("void_reason", "TEXT", notNull = false),
            column("voided_at", "INTEGER", notNull = false),
        ),
    indices =
        listOf(
            index("index_stock_transfers_business_date", key("business_date")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("from_location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("to_location_id"),
                referencedTable = "locations",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun supplierLedgerEntriesTable(): CatalogTable = CatalogTable(
    name = "supplier_ledger_entries",
    autoIncrement = true,
    columns =
        listOf(
            column("amount", "INTEGER", notNull = true),
            column("balance_after", "INTEGER", notNull = true),
            column("business_date", "TEXT", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("due_date", "TEXT", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("note", "TEXT", notNull = false),
            column("occurred_at", "INTEGER", notNull = true),
            column("purchase_id", "INTEGER", notNull = false),
            column("purchase_return_id", "INTEGER", notNull = false),
            column("reverses_entry_id", "INTEGER", notNull = false),
            column("supplier_id", "INTEGER", notNull = true),
            column("type", "TEXT", notNull = true),
        ),
    indices =
        listOf(
            index("index_supplier_ledger_entries_due_date", key("due_date")),
            index("index_supplier_ledger_entries_purchase_id", key("purchase_id")),
            uniqueIndex(
                "index_supplier_ledger_entries_reverses_entry_id",
                key("reverses_entry_id"),
            ),
            index("index_supplier_ledger_entries_supplier_id", key("supplier_id")),
        ),
    foreignKeys =
        listOf(
            CatalogForeignKey(
                columns = listOf("purchase_id"),
                referencedTable = "purchases",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("purchase_return_id"),
                referencedTable = "purchase_returns",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("reverses_entry_id"),
                referencedTable = "supplier_ledger_entries",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
            CatalogForeignKey(
                columns = listOf("supplier_id"),
                referencedTable = "suppliers",
                referencedColumns = listOf("id"),
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        ),
)

private fun suppliersTable(): CatalogTable = CatalogTable(
    name = "suppliers",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("balance", "INTEGER", notNull = true),
            column("created_at", "INTEGER", notNull = true),
            column("email", "TEXT", notNull = false),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("name", "TEXT", notNull = true),
            column("normalized_name", "TEXT", notNull = true),
            column("notes", "TEXT", notNull = false),
            column("payment_terms_days", "INTEGER", notNull = false),
            column("phone", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_suppliers_normalized_name", key("normalized_name")),
        ),
    foreignKeys = emptyList(),
)

private fun tagsTable(): CatalogTable = CatalogTable(
    name = "tags",
    autoIncrement = true,
    columns =
        listOf(
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("name", "TEXT", notNull = true),
            column("normalized_name", "TEXT", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            uniqueIndex("index_tags_normalized_name", key("normalized_name")),
        ),
    foreignKeys = emptyList(),
)

private fun taxRatesTable(): CatalogTable = CatalogTable(
    name = "tax_rates",
    autoIncrement = true,
    columns =
        listOf(
            column("archived_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("name", "TEXT", notNull = true),
            column("normalized_name", "TEXT", notNull = true),
            column("rate_bp", "INTEGER", notNull = true),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_tax_rates_normalized_name", key("normalized_name")),
        ),
    foreignKeys = emptyList(),
)

private fun unitsTable(): CatalogTable = CatalogTable(
    name = "units",
    autoIncrement = true,
    columns =
        listOf(
            column("allows_fraction", "INTEGER", notNull = true),
            column("archived_at", "INTEGER", notNull = false),
            column("created_at", "INTEGER", notNull = true),
            column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            column("kind", "TEXT", notNull = true),
            column("name", "TEXT", notNull = false),
            column("normalized_name", "TEXT", notNull = false),
            column("symbol", "TEXT", notNull = false),
            column("system_key", "TEXT", notNull = false),
            column("updated_at", "INTEGER", notNull = true),
        ),
    indices =
        listOf(
            index("index_units_normalized_name", key("normalized_name")),
            uniqueIndex("index_units_system_key", key("system_key")),
        ),
    foreignKeys = emptyList(),
)

/** Las guardas, con el texto exacto que guarda SQLite. */
private fun triggers(): List<CatalogTrigger> = listOf(
    CatalogTrigger(
        name = "guard_app_settings_insert",
        table = "app_settings",
        sql =
            """
                CREATE TRIGGER guard_app_settings_insert BEFORE INSERT ON app_settings
                WHEN NOT IFNULL((
                  (NEW.business_name IS NULL OR (typeof(NEW.business_name) = 'text' AND length(NEW.business_name) BETWEEN 1 AND 80))
                  AND typeof(NEW.default_target_margin_bp) = 'integer' AND NEW.default_target_margin_bp BETWEEN 0 AND 9900
                  AND NEW.low_stock_notifications_enabled IN (0, 1)
                  AND NEW.theme_mode IN ('SYSTEM', 'LIGHT', 'DARK')
                  AND NEW.color_palette IN ('INDIGO_MUSTARD', 'BERRY_HONEY', 'RASPBERRY_GOLD', 'WINE_CHAMPAGNE', 'PLUM_CORAL', 'FOREST_HONEY', 'CARMINE_GOLD', 'GARNET_HONEY', 'VIVID_RED_CREAM')
                  AND NEW.app_lock_enabled IN (0, 1)
                  AND NEW.app_lock_timeout_seconds IN (0, 60, 300)
                  AND NEW.metered_backup_policy IN ('EVERY_60_MIN', 'EVERY_30_MIN', 'WIFI_ONLY')
                  AND (NEW.onboarding_completed_at IS NULL OR (typeof(NEW.onboarding_completed_at) = 'integer'))
                  AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
                  AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
                  AND (NEW.currency_symbol IS NULL OR (typeof(NEW.currency_symbol) = 'text' AND length(NEW.currency_symbol) BETWEEN 1 AND 8))
                  AND (NEW.currency_locked_at IS NULL OR (typeof(NEW.currency_locked_at) = 'integer'))
                  AND typeof(NEW.cash_rounding) = 'integer' AND NEW.cash_rounding BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.format_locale) = 'text' AND length(NEW.format_locale) BETWEEN 1 AND 35
                  AND typeof(NEW.business_time_zone) = 'text' AND length(NEW.business_time_zone) BETWEEN 1 AND 64
                  AND typeof(NEW.week_start_day) = 'integer' AND NEW.week_start_day BETWEEN 1 AND 7
                  AND NEW.csv_separator IN ('SEMICOLON', 'COMMA', 'TAB')
                  AND NEW.tax_mode IN ('NONE', 'INCLUDED', 'ADDED')
                  AND NEW.purchase_tax_recoverable IN (0, 1)
                  AND typeof(NEW.default_rounding_step) = 'integer' AND NEW.default_rounding_step BETWEEN 0 AND 1000000000000
                  AND NEW.tips_enabled IN (0, 1)
                  AND (NEW.reference_currency_code IS NULL OR (length(NEW.reference_currency_code) = 3 AND NEW.reference_currency_code = upper(NEW.reference_currency_code) AND NEW.reference_currency_code NOT GLOB '*[^A-Z]*'))
                  AND (NEW.reference_currency_decimals IS NULL OR (typeof(NEW.reference_currency_decimals) = 'integer' AND NEW.reference_currency_decimals BETWEEN 0 AND 4))
                  AND typeof(NEW.cost_stale_days) = 'integer' AND NEW.cost_stale_days BETWEEN 0 AND 36500
                  AND typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500
                  AND NEW.loyalty_enabled IN (0, 1)
                  AND (NEW.loyalty_amount_per_point IS NULL OR (typeof(NEW.loyalty_amount_per_point) = 'integer' AND NEW.loyalty_amount_per_point BETWEEN 1 AND 1000000000000))
                  AND (NEW.loyalty_point_value IS NULL OR (typeof(NEW.loyalty_point_value) = 'integer' AND NEW.loyalty_point_value BETWEEN 1 AND 1000000000000))
                  AND (NEW.revenue_limit_amount IS NULL OR (typeof(NEW.revenue_limit_amount) = 'integer' AND NEW.revenue_limit_amount BETWEEN 1 AND 9000000000000000))
                  AND (NEW.revenue_limit_months IS NULL OR (typeof(NEW.revenue_limit_months) = 'integer' AND NEW.revenue_limit_months BETWEEN 1 AND 120))
                  AND (NEW.daily_summary_time IS NULL OR (length(NEW.daily_summary_time) = 5 AND NEW.daily_summary_time GLOB '[0-2][0-9]:[0-5][0-9]' AND substr(NEW.daily_summary_time, 1, 2) <= '23'))
                  AND (NEW.receipt_business_details IS NULL OR (typeof(NEW.receipt_business_details) = 'text' AND length(NEW.receipt_business_details) BETWEEN 1 AND 300))
                  AND (NEW.receipt_footer_text IS NULL OR (typeof(NEW.receipt_footer_text) = 'text' AND length(NEW.receipt_footer_text) BETWEEN 1 AND 300))
                  AND (NEW.fiscal_portal_url IS NULL OR (typeof(NEW.fiscal_portal_url) = 'text' AND length(NEW.fiscal_portal_url) BETWEEN 1 AND 300))
                  AND NEW.discreet_mode_enabled IN (0, 1)
                  AND NEW.media_on_metered IN (0, 1)
                  AND NEW.update_check_enabled IN (0, 1)
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.id = 1
                  AND (NEW.reference_currency_code IS NULL) = (NEW.reference_currency_decimals IS NULL)
                  AND (NEW.loyalty_enabled = 0 OR (NEW.loyalty_amount_per_point IS NOT NULL AND NEW.loyalty_point_value IS NOT NULL))
                  AND (NEW.revenue_limit_amount IS NULL) = (NEW.revenue_limit_months IS NULL)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:app_settings'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_app_settings_no_delete",
        table = "app_settings",
        sql =
            """
                CREATE TRIGGER guard_app_settings_no_delete BEFORE DELETE ON app_settings
                BEGIN SELECT RAISE(ABORT, 'guard:app_settings_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_app_settings_update",
        table = "app_settings",
        sql =
            """
                CREATE TRIGGER guard_app_settings_update BEFORE UPDATE ON app_settings
                WHEN NOT IFNULL((
                  (NEW.business_name IS NULL OR (typeof(NEW.business_name) = 'text' AND length(NEW.business_name) BETWEEN 1 AND 80))
                  AND typeof(NEW.default_target_margin_bp) = 'integer' AND NEW.default_target_margin_bp BETWEEN 0 AND 9900
                  AND NEW.low_stock_notifications_enabled IN (0, 1)
                  AND NEW.theme_mode IN ('SYSTEM', 'LIGHT', 'DARK')
                  AND NEW.color_palette IN ('INDIGO_MUSTARD', 'BERRY_HONEY', 'RASPBERRY_GOLD', 'WINE_CHAMPAGNE', 'PLUM_CORAL', 'FOREST_HONEY', 'CARMINE_GOLD', 'GARNET_HONEY', 'VIVID_RED_CREAM')
                  AND NEW.app_lock_enabled IN (0, 1)
                  AND NEW.app_lock_timeout_seconds IN (0, 60, 300)
                  AND NEW.metered_backup_policy IN ('EVERY_60_MIN', 'EVERY_30_MIN', 'WIFI_ONLY')
                  AND (NEW.onboarding_completed_at IS NULL OR (typeof(NEW.onboarding_completed_at) = 'integer'))
                  AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
                  AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
                  AND (NEW.currency_symbol IS NULL OR (typeof(NEW.currency_symbol) = 'text' AND length(NEW.currency_symbol) BETWEEN 1 AND 8))
                  AND (NEW.currency_locked_at IS NULL OR (typeof(NEW.currency_locked_at) = 'integer'))
                  AND typeof(NEW.cash_rounding) = 'integer' AND NEW.cash_rounding BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.format_locale) = 'text' AND length(NEW.format_locale) BETWEEN 1 AND 35
                  AND typeof(NEW.business_time_zone) = 'text' AND length(NEW.business_time_zone) BETWEEN 1 AND 64
                  AND typeof(NEW.week_start_day) = 'integer' AND NEW.week_start_day BETWEEN 1 AND 7
                  AND NEW.csv_separator IN ('SEMICOLON', 'COMMA', 'TAB')
                  AND NEW.tax_mode IN ('NONE', 'INCLUDED', 'ADDED')
                  AND NEW.purchase_tax_recoverable IN (0, 1)
                  AND typeof(NEW.default_rounding_step) = 'integer' AND NEW.default_rounding_step BETWEEN 0 AND 1000000000000
                  AND NEW.tips_enabled IN (0, 1)
                  AND (NEW.reference_currency_code IS NULL OR (length(NEW.reference_currency_code) = 3 AND NEW.reference_currency_code = upper(NEW.reference_currency_code) AND NEW.reference_currency_code NOT GLOB '*[^A-Z]*'))
                  AND (NEW.reference_currency_decimals IS NULL OR (typeof(NEW.reference_currency_decimals) = 'integer' AND NEW.reference_currency_decimals BETWEEN 0 AND 4))
                  AND typeof(NEW.cost_stale_days) = 'integer' AND NEW.cost_stale_days BETWEEN 0 AND 36500
                  AND typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500
                  AND NEW.loyalty_enabled IN (0, 1)
                  AND (NEW.loyalty_amount_per_point IS NULL OR (typeof(NEW.loyalty_amount_per_point) = 'integer' AND NEW.loyalty_amount_per_point BETWEEN 1 AND 1000000000000))
                  AND (NEW.loyalty_point_value IS NULL OR (typeof(NEW.loyalty_point_value) = 'integer' AND NEW.loyalty_point_value BETWEEN 1 AND 1000000000000))
                  AND (NEW.revenue_limit_amount IS NULL OR (typeof(NEW.revenue_limit_amount) = 'integer' AND NEW.revenue_limit_amount BETWEEN 1 AND 9000000000000000))
                  AND (NEW.revenue_limit_months IS NULL OR (typeof(NEW.revenue_limit_months) = 'integer' AND NEW.revenue_limit_months BETWEEN 1 AND 120))
                  AND (NEW.daily_summary_time IS NULL OR (length(NEW.daily_summary_time) = 5 AND NEW.daily_summary_time GLOB '[0-2][0-9]:[0-5][0-9]' AND substr(NEW.daily_summary_time, 1, 2) <= '23'))
                  AND (NEW.receipt_business_details IS NULL OR (typeof(NEW.receipt_business_details) = 'text' AND length(NEW.receipt_business_details) BETWEEN 1 AND 300))
                  AND (NEW.receipt_footer_text IS NULL OR (typeof(NEW.receipt_footer_text) = 'text' AND length(NEW.receipt_footer_text) BETWEEN 1 AND 300))
                  AND (NEW.fiscal_portal_url IS NULL OR (typeof(NEW.fiscal_portal_url) = 'text' AND length(NEW.fiscal_portal_url) BETWEEN 1 AND 300))
                  AND NEW.discreet_mode_enabled IN (0, 1)
                  AND NEW.media_on_metered IN (0, 1)
                  AND NEW.update_check_enabled IN (0, 1)
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.id = 1
                  AND (NEW.reference_currency_code IS NULL) = (NEW.reference_currency_decimals IS NULL)
                  AND (NEW.loyalty_enabled = 0 OR (NEW.loyalty_amount_per_point IS NOT NULL AND NEW.loyalty_point_value IS NOT NULL))
                  AND (NEW.revenue_limit_amount IS NULL) = (NEW.revenue_limit_months IS NULL)
                  AND (OLD.currency_locked_at IS NULL OR (NEW.currency_decimals = OLD.currency_decimals AND NEW.currency_locked_at IS OLD.currency_locked_at))
                  AND (NEW.currency_locked_at IS NULL OR NEW.currency_code <> 'XXX')
                  AND (NEW.currency_code = OLD.currency_code OR OLD.currency_locked_at IS NULL OR ((NEW.reference_currency_code IS NULL OR NEW.reference_currency_code <> NEW.currency_code) AND NOT EXISTS (SELECT 1 FROM money_accounts a WHERE a.currency_code = NEW.currency_code)))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:app_settings'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_audit_events_insert",
        table = "audit_events",
        sql =
            """
                CREATE TRIGGER guard_audit_events_insert BEFORE INSERT ON audit_events
                WHEN NOT IFNULL((
                  typeof(NEW.occurred_at) = 'integer'
                  AND NEW.event_type IN ('EXPORT_CSV', 'EXPORT_FULL_COPY', 'RESTORE', 'RESTORE_REJECTED', 'DESTINATION_DISCONNECTED', 'DESTINATION_CHANGED', 'APP_LOCK_CHANGED', 'ENCRYPTION_CHANGED', 'RECOVERY_CODE_VIEWED', 'DELETE_MY_DATA', 'AUTH_FAILED', 'CURRENCY_CORRECTED')
                  AND NEW.result IN ('SUCCESS', 'FAILURE')
                  AND NOT EXISTS (SELECT 1 FROM audit_events x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:audit_events'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_audit_events_no_delete",
        table = "audit_events",
        sql =
            """
                CREATE TRIGGER guard_audit_events_no_delete BEFORE DELETE ON audit_events
                WHEN (SELECT COUNT(*) FROM audit_events) <= 5000
                BEGIN SELECT RAISE(ABORT, 'guard:audit_events_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_audit_events_no_update",
        table = "audit_events",
        sql =
            """
                CREATE TRIGGER guard_audit_events_no_update BEFORE UPDATE ON audit_events
                BEGIN SELECT RAISE(ABORT, 'guard:audit_events_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_backup_state_insert",
        table = "backup_state",
        sql =
            """
                CREATE TRIGGER guard_backup_state_insert BEFORE INSERT ON backup_state
                WHEN NOT IFNULL((
                  typeof(NEW.change_seq) = 'integer' AND NEW.change_seq >= 0
                  AND typeof(NEW.verified_seq) = 'integer' AND NEW.verified_seq >= 0
                  AND (NEW.first_unsynced_at IS NULL OR (typeof(NEW.first_unsynced_at) = 'integer'))
                  AND NEW.link_state IN ('NOT_CONNECTED', 'CONNECTED', 'PERMISSION_LOST', 'CONFLICT')
                  AND (NEW.failure_code IS NULL OR (NEW.failure_code IN ('QUOTA_EXCEEDED', 'LOCAL_CORRUPT', 'LOCAL_STORAGE_LOW', 'LOCAL_INVARIANT', 'LOCAL_INVARIANT_FATAL', 'VERIFY_MISMATCH', 'BACKUP_TOO_LARGE', 'CLOUD_SCHEMA_NEWER', 'DESTINATION_UNSUPPORTED', 'TRANSPORT_PERSISTENT', 'ENCRYPTION_KEY_UNAVAILABLE', 'UNEXPECTED')))
                  AND typeof(NEW.consecutive_failures) = 'integer' AND NEW.consecutive_failures >= 0
                  AND (NEW.last_failure_kind IS NULL OR (NEW.last_failure_kind IN ('NETWORK', 'TLS', 'HTTP_5XX', 'RATE_LIMIT')))
                  AND typeof(NEW.invariant_failures) = 'integer' AND NEW.invariant_failures >= 0
                  AND (NEW.last_attempt_at IS NULL OR (typeof(NEW.last_attempt_at) = 'integer'))
                  AND (NEW.last_success_at IS NULL OR (typeof(NEW.last_success_at) = 'integer'))
                  AND (NEW.last_upload_finished_at IS NULL OR (typeof(NEW.last_upload_finished_at) = 'integer'))
                  AND (NEW.last_stale_notice_at IS NULL OR (typeof(NEW.last_stale_notice_at) = 'integer'))
                  AND (NEW.last_drill_at IS NULL OR (typeof(NEW.last_drill_at) = 'integer'))
                  AND (NEW.last_drill_result IS NULL OR (NEW.last_drill_result IN ('PASSED', 'FAILED', 'SKIPPED')))
                  AND (NEW.last_full_verify_at IS NULL OR (typeof(NEW.last_full_verify_at) = 'integer'))
                  AND (NEW.last_human_check_at IS NULL OR (typeof(NEW.last_human_check_at) = 'integer'))
                  AND (NEW.human_check_generation IS NULL OR (typeof(NEW.human_check_generation) = 'integer' AND NEW.human_check_generation >= 0))
                  AND (NEW.destination_kind IS NULL OR (NEW.destination_kind IN ('WEBDAV', 'DOCUMENT_SLOTS')))
                  AND (NEW.destination_account IS NULL OR (typeof(NEW.destination_account) = 'text' AND length(NEW.destination_account) BETWEEN 1 AND 254))
                  AND (NEW.destination_id IS NULL OR (typeof(NEW.destination_id) = 'text' AND length(NEW.destination_id) BETWEEN 1 AND 64))
                  AND (NEW.destination_folder IS NULL OR (typeof(NEW.destination_folder) = 'text' AND length(NEW.destination_folder) BETWEEN 1 AND 1024))
                  AND typeof(NEW.head_generation) = 'integer' AND NEW.head_generation >= 0
                  AND (NEW.head_device_id IS NULL OR (typeof(NEW.head_device_id) = 'text' AND length(NEW.head_device_id) BETWEEN 1 AND 64))
                  AND (NEW.head_file_ref IS NULL OR (typeof(NEW.head_file_ref) = 'text' AND length(NEW.head_file_ref) BETWEEN 1 AND 1024))
                  AND (NEW.shrink_accepted_generation IS NULL OR (typeof(NEW.shrink_accepted_generation) = 'integer'))
                  AND (NEW.pending_generation IS NULL OR (typeof(NEW.pending_generation) = 'integer'))
                  AND (NEW.pending_seq IS NULL OR (typeof(NEW.pending_seq) = 'integer'))
                  AND (NEW.pending_sha256 IS NULL OR (typeof(NEW.pending_sha256) = 'text' AND length(NEW.pending_sha256) BETWEEN 1 AND 64))
                  AND (NEW.pending_size_bytes IS NULL OR (typeof(NEW.pending_size_bytes) = 'integer'))
                  AND (NEW.conflict_kind IS NULL OR (NEW.conflict_kind IN ('CLOUD_AHEAD', 'OTHER_DEVICE', 'LOCAL_SHRUNK')))
                  AND (NEW.conflict_generation IS NULL OR (typeof(NEW.conflict_generation) = 'integer'))
                  AND (NEW.conflict_device_id IS NULL OR (typeof(NEW.conflict_device_id) = 'text' AND length(NEW.conflict_device_id) BETWEEN 1 AND 64))
                  AND (NEW.conflict_file_ref IS NULL OR (typeof(NEW.conflict_file_ref) = 'text' AND length(NEW.conflict_file_ref) BETWEEN 1 AND 1024))
                  AND (NEW.copy_generation IS NULL OR (typeof(NEW.copy_generation) = 'integer'))
                  AND (NEW.copy_device_id IS NULL OR (typeof(NEW.copy_device_id) = 'text' AND length(NEW.copy_device_id) BETWEEN 1 AND 64))
                  AND (NEW.copy_created_at IS NULL OR (typeof(NEW.copy_created_at) = 'integer'))
                  AND NEW.id = 1
                  AND NEW.verified_seq <= NEW.change_seq
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:backup_state'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_backup_state_no_delete",
        table = "backup_state",
        sql =
            """
                CREATE TRIGGER guard_backup_state_no_delete BEFORE DELETE ON backup_state
                BEGIN SELECT RAISE(ABORT, 'guard:backup_state_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_backup_state_update",
        table = "backup_state",
        sql =
            """
                CREATE TRIGGER guard_backup_state_update BEFORE UPDATE ON backup_state
                WHEN NOT IFNULL((
                  typeof(NEW.change_seq) = 'integer' AND NEW.change_seq >= 0
                  AND typeof(NEW.verified_seq) = 'integer' AND NEW.verified_seq >= 0
                  AND (NEW.first_unsynced_at IS NULL OR (typeof(NEW.first_unsynced_at) = 'integer'))
                  AND NEW.link_state IN ('NOT_CONNECTED', 'CONNECTED', 'PERMISSION_LOST', 'CONFLICT')
                  AND (NEW.failure_code IS NULL OR (NEW.failure_code IN ('QUOTA_EXCEEDED', 'LOCAL_CORRUPT', 'LOCAL_STORAGE_LOW', 'LOCAL_INVARIANT', 'LOCAL_INVARIANT_FATAL', 'VERIFY_MISMATCH', 'BACKUP_TOO_LARGE', 'CLOUD_SCHEMA_NEWER', 'DESTINATION_UNSUPPORTED', 'TRANSPORT_PERSISTENT', 'ENCRYPTION_KEY_UNAVAILABLE', 'UNEXPECTED')))
                  AND typeof(NEW.consecutive_failures) = 'integer' AND NEW.consecutive_failures >= 0
                  AND (NEW.last_failure_kind IS NULL OR (NEW.last_failure_kind IN ('NETWORK', 'TLS', 'HTTP_5XX', 'RATE_LIMIT')))
                  AND typeof(NEW.invariant_failures) = 'integer' AND NEW.invariant_failures >= 0
                  AND (NEW.last_attempt_at IS NULL OR (typeof(NEW.last_attempt_at) = 'integer'))
                  AND (NEW.last_success_at IS NULL OR (typeof(NEW.last_success_at) = 'integer'))
                  AND (NEW.last_upload_finished_at IS NULL OR (typeof(NEW.last_upload_finished_at) = 'integer'))
                  AND (NEW.last_stale_notice_at IS NULL OR (typeof(NEW.last_stale_notice_at) = 'integer'))
                  AND (NEW.last_drill_at IS NULL OR (typeof(NEW.last_drill_at) = 'integer'))
                  AND (NEW.last_drill_result IS NULL OR (NEW.last_drill_result IN ('PASSED', 'FAILED', 'SKIPPED')))
                  AND (NEW.last_full_verify_at IS NULL OR (typeof(NEW.last_full_verify_at) = 'integer'))
                  AND (NEW.last_human_check_at IS NULL OR (typeof(NEW.last_human_check_at) = 'integer'))
                  AND (NEW.human_check_generation IS NULL OR (typeof(NEW.human_check_generation) = 'integer' AND NEW.human_check_generation >= 0))
                  AND (NEW.destination_kind IS NULL OR (NEW.destination_kind IN ('WEBDAV', 'DOCUMENT_SLOTS')))
                  AND (NEW.destination_account IS NULL OR (typeof(NEW.destination_account) = 'text' AND length(NEW.destination_account) BETWEEN 1 AND 254))
                  AND (NEW.destination_id IS NULL OR (typeof(NEW.destination_id) = 'text' AND length(NEW.destination_id) BETWEEN 1 AND 64))
                  AND (NEW.destination_folder IS NULL OR (typeof(NEW.destination_folder) = 'text' AND length(NEW.destination_folder) BETWEEN 1 AND 1024))
                  AND typeof(NEW.head_generation) = 'integer' AND NEW.head_generation >= 0
                  AND (NEW.head_device_id IS NULL OR (typeof(NEW.head_device_id) = 'text' AND length(NEW.head_device_id) BETWEEN 1 AND 64))
                  AND (NEW.head_file_ref IS NULL OR (typeof(NEW.head_file_ref) = 'text' AND length(NEW.head_file_ref) BETWEEN 1 AND 1024))
                  AND (NEW.shrink_accepted_generation IS NULL OR (typeof(NEW.shrink_accepted_generation) = 'integer'))
                  AND (NEW.pending_generation IS NULL OR (typeof(NEW.pending_generation) = 'integer'))
                  AND (NEW.pending_seq IS NULL OR (typeof(NEW.pending_seq) = 'integer'))
                  AND (NEW.pending_sha256 IS NULL OR (typeof(NEW.pending_sha256) = 'text' AND length(NEW.pending_sha256) BETWEEN 1 AND 64))
                  AND (NEW.pending_size_bytes IS NULL OR (typeof(NEW.pending_size_bytes) = 'integer'))
                  AND (NEW.conflict_kind IS NULL OR (NEW.conflict_kind IN ('CLOUD_AHEAD', 'OTHER_DEVICE', 'LOCAL_SHRUNK')))
                  AND (NEW.conflict_generation IS NULL OR (typeof(NEW.conflict_generation) = 'integer'))
                  AND (NEW.conflict_device_id IS NULL OR (typeof(NEW.conflict_device_id) = 'text' AND length(NEW.conflict_device_id) BETWEEN 1 AND 64))
                  AND (NEW.conflict_file_ref IS NULL OR (typeof(NEW.conflict_file_ref) = 'text' AND length(NEW.conflict_file_ref) BETWEEN 1 AND 1024))
                  AND (NEW.copy_generation IS NULL OR (typeof(NEW.copy_generation) = 'integer'))
                  AND (NEW.copy_device_id IS NULL OR (typeof(NEW.copy_device_id) = 'text' AND length(NEW.copy_device_id) BETWEEN 1 AND 64))
                  AND (NEW.copy_created_at IS NULL OR (typeof(NEW.copy_created_at) = 'integer'))
                  AND NEW.id = 1
                  AND NEW.verified_seq <= NEW.change_seq
                  AND NEW.change_seq >= OLD.change_seq
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:backup_state'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_business_goals_insert",
        table = "business_goals",
        sql =
            """
                CREATE TRIGGER guard_business_goals_insert BEFORE INSERT ON business_goals
                WHEN NOT IFNULL((
                  length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
                  AND (NEW.sales_goal IS NULL OR (typeof(NEW.sales_goal) = 'integer' AND NEW.sales_goal BETWEEN 0 AND 9000000000000000))
                  AND (NEW.profit_goal IS NULL OR (typeof(NEW.profit_goal) = 'integer' AND NEW.profit_goal BETWEEN -9000000000000000 AND 9000000000000000))
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:business_goals'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_business_goals_update",
        table = "business_goals",
        sql =
            """
                CREATE TRIGGER guard_business_goals_update BEFORE UPDATE ON business_goals
                WHEN NOT IFNULL((
                  length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
                  AND (NEW.sales_goal IS NULL OR (typeof(NEW.sales_goal) = 'integer' AND NEW.sales_goal BETWEEN 0 AND 9000000000000000))
                  AND (NEW.profit_goal IS NULL OR (typeof(NEW.profit_goal) = 'integer' AND NEW.profit_goal BETWEEN -9000000000000000 AND 9000000000000000))
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:business_goals'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_cash_denominations_insert",
        table = "cash_denominations",
        sql =
            """
                CREATE TRIGGER guard_cash_denominations_insert BEFORE INSERT ON cash_denominations
                WHEN NOT IFNULL((
                  typeof(NEW.value) = 'integer' AND NEW.value BETWEEN 1 AND 1000000000000
                  AND NEW.is_active IN (0, 1)
                  AND typeof(NEW.sort_order) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:cash_denominations'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_cash_denominations_update",
        table = "cash_denominations",
        sql =
            """
                CREATE TRIGGER guard_cash_denominations_update BEFORE UPDATE ON cash_denominations
                WHEN NOT IFNULL((
                  typeof(NEW.value) = 'integer' AND NEW.value BETWEEN 1 AND 1000000000000
                  AND NEW.is_active IN (0, 1)
                  AND typeof(NEW.sort_order) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:cash_denominations'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_cash_session_counts_insert",
        table = "cash_session_counts",
        sql =
            """
                CREATE TRIGGER guard_cash_session_counts_insert BEFORE INSERT ON cash_session_counts
                WHEN NOT IFNULL((
                  NEW.phase IN ('OPENING', 'CLOSING')
                  AND typeof(NEW.count) = 'integer' AND NEW.count BETWEEN 0 AND 1000000000
                  AND NOT EXISTS (SELECT 1 FROM cash_session_counts x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:cash_session_counts'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_cash_session_counts_no_delete",
        table = "cash_session_counts",
        sql =
            """
                CREATE TRIGGER guard_cash_session_counts_no_delete BEFORE DELETE ON cash_session_counts
                BEGIN SELECT RAISE(ABORT, 'guard:cash_session_counts_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_cash_session_counts_no_update",
        table = "cash_session_counts",
        sql =
            """
                CREATE TRIGGER guard_cash_session_counts_no_update BEFORE UPDATE ON cash_session_counts
                BEGIN SELECT RAISE(ABORT, 'guard:cash_session_counts_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_cash_sessions_insert",
        table = "cash_sessions",
        sql =
            """
                CREATE TRIGGER guard_cash_sessions_insert BEFORE INSERT ON cash_sessions
                WHEN NOT IFNULL((
                  NEW.status IN ('OPEN', 'CLOSED')
                  AND typeof(NEW.opening_amount) = 'integer'
                  AND (NEW.expected_amount IS NULL OR (typeof(NEW.expected_amount) = 'integer'))
                  AND (NEW.counted_amount IS NULL OR (typeof(NEW.counted_amount) = 'integer'))
                  AND (NEW.difference_amount IS NULL OR (typeof(NEW.difference_amount) = 'integer'))
                  AND typeof(NEW.opened_at) = 'integer'
                  AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
                  AND date(NEW.business_date) = NEW.business_date
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND NEW.status = 'OPEN'
                  AND NEW.closed_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM cash_sessions c WHERE c.money_account_id = NEW.money_account_id AND c.status = 'OPEN')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:cash_sessions'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_cash_sessions_update",
        table = "cash_sessions",
        sql =
            """
                CREATE TRIGGER guard_cash_sessions_update BEFORE UPDATE ON cash_sessions
                WHEN NOT IFNULL((
                  NEW.status IN ('OPEN', 'CLOSED')
                  AND typeof(NEW.opening_amount) = 'integer'
                  AND (NEW.expected_amount IS NULL OR (typeof(NEW.expected_amount) = 'integer'))
                  AND (NEW.counted_amount IS NULL OR (typeof(NEW.counted_amount) = 'integer'))
                  AND (NEW.difference_amount IS NULL OR (typeof(NEW.difference_amount) = 'integer'))
                  AND typeof(NEW.opened_at) = 'integer'
                  AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
                  AND date(NEW.business_date) = NEW.business_date
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND OLD.status = 'OPEN'
                  AND NEW.id = OLD.id AND NEW.money_account_id = OLD.money_account_id AND NEW.opening_amount = OLD.opening_amount AND NEW.opened_at = OLD.opened_at
                  AND (NEW.status = 'OPEN' OR (NEW.closed_at IS NOT NULL AND NEW.expected_amount IS NOT NULL AND NEW.counted_amount IS NOT NULL AND NEW.difference_amount = NEW.counted_amount - NEW.expected_amount))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:cash_sessions'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_categories_insert",
        table = "categories",
        sql =
            """
                CREATE TRIGGER guard_categories_insert BEFORE INSERT ON categories
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:categories'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_categories_update",
        table = "categories",
        sql =
            """
                CREATE TRIGGER guard_categories_update BEFORE UPDATE ON categories
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:categories'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_count_session_lines_insert",
        table = "count_session_lines",
        sql =
            """
                CREATE TRIGGER guard_count_session_lines_insert BEFORE INSERT ON count_session_lines
                WHEN NOT IFNULL((
                  typeof(NEW.expected_milli) = 'integer'
                  AND (NEW.counted_milli IS NULL OR (typeof(NEW.counted_milli) = 'integer' AND NEW.counted_milli BETWEEN 0 AND 8000000000))
                  AND EXISTS (SELECT 1 FROM count_sessions c WHERE c.id = NEW.count_session_id AND c.status = 'OPEN')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:count_session_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_count_session_lines_update",
        table = "count_session_lines",
        sql =
            """
                CREATE TRIGGER guard_count_session_lines_update BEFORE UPDATE ON count_session_lines
                WHEN NOT IFNULL((
                  typeof(NEW.expected_milli) = 'integer'
                  AND (NEW.counted_milli IS NULL OR (typeof(NEW.counted_milli) = 'integer' AND NEW.counted_milli BETWEEN 0 AND 8000000000))
                  AND EXISTS (SELECT 1 FROM count_sessions c WHERE c.id = NEW.count_session_id AND c.status = 'OPEN')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:count_session_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_count_sessions_insert",
        table = "count_sessions",
        sql =
            """
                CREATE TRIGGER guard_count_sessions_insert BEFORE INSERT ON count_sessions
                WHEN NOT IFNULL((
                  NEW.status IN ('OPEN', 'APPLIED', 'CANCELLED')
                  AND NEW.is_blind IN (0, 1)
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.started_at) = 'integer'
                  AND (NEW.finished_at IS NULL OR (typeof(NEW.finished_at) = 'integer'))
                  AND date(NEW.business_date) = NEW.business_date
                  AND NEW.status = 'OPEN'
                  AND NEW.finished_at IS NULL
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:count_sessions'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_count_sessions_update",
        table = "count_sessions",
        sql =
            """
                CREATE TRIGGER guard_count_sessions_update BEFORE UPDATE ON count_sessions
                WHEN NOT IFNULL((
                  NEW.status IN ('OPEN', 'APPLIED', 'CANCELLED')
                  AND NEW.is_blind IN (0, 1)
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.started_at) = 'integer'
                  AND (NEW.finished_at IS NULL OR (typeof(NEW.finished_at) = 'integer'))
                  AND date(NEW.business_date) = NEW.business_date
                  AND NEW.id = OLD.id AND NEW.location_id = OLD.location_id AND NEW.is_blind = OLD.is_blind AND NEW.started_at = OLD.started_at
                  AND (OLD.status = 'OPEN' OR (NEW.status = OLD.status AND NEW.finished_at IS OLD.finished_at))
                  AND (NEW.status = 'OPEN') = (NEW.finished_at IS NULL)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:count_sessions'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_custom_field_definitions_insert",
        table = "custom_field_definitions",
        sql =
            """
                CREATE TRIGGER guard_custom_field_definitions_insert BEFORE INSERT ON custom_field_definitions
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND NEW.field_type IN ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN')
                  AND typeof(NEW.sort_order) = 'integer'
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM custom_field_definitions f WHERE f.normalized_name = NEW.normalized_name AND f.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:custom_field_definitions'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_custom_field_definitions_update",
        table = "custom_field_definitions",
        sql =
            """
                CREATE TRIGGER guard_custom_field_definitions_update BEFORE UPDATE ON custom_field_definitions
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND NEW.field_type IN ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN')
                  AND typeof(NEW.sort_order) = 'integer'
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM custom_field_definitions f WHERE f.normalized_name = NEW.normalized_name AND f.archived_at IS NULL AND f.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:custom_field_definitions'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_customer_ledger_entries_insert",
        table = "customer_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_customer_ledger_entries_insert BEFORE INSERT ON customer_ledger_entries
                WHEN NOT IFNULL((
                  NEW.type IN ('OPENING_BALANCE', 'CREDIT_SALE', 'PAYMENT', 'RETURN_CREDIT', 'ADJUSTMENT', 'ENTRY_VOID')
                  AND typeof(NEW.amount) = 'integer'
                  AND typeof(NEW.balance_after) = 'integer'
                  AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
                  AND NEW.amount <> 0
                  AND CASE NEW.type WHEN 'CREDIT_SALE' THEN NEW.amount > 0 WHEN 'PAYMENT' THEN NEW.amount < 0 WHEN 'RETURN_CREDIT' THEN NEW.amount < 0 ELSE 1 END
                  AND (NEW.type = 'CREDIT_SALE') = (NEW.sale_id IS NOT NULL)
                  AND (NEW.type = 'RETURN_CREDIT') = (NEW.sale_return_id IS NOT NULL)
                  AND (NEW.type = 'ENTRY_VOID') = (NEW.reverses_entry_id IS NOT NULL)
                  AND (NEW.type <> 'ADJUSTMENT' OR NEW.note IS NOT NULL)
                  AND (NEW.reverses_entry_id IS NULL OR EXISTS (SELECT 1 FROM customer_ledger_entries o WHERE o.id = NEW.reverses_entry_id AND o.customer_id = NEW.customer_id AND o.amount = -NEW.amount AND o.type <> 'ENTRY_VOID'))
                  AND NOT EXISTS (SELECT 1 FROM customer_ledger_entries e WHERE e.reverses_entry_id = NEW.reverses_entry_id)
                  AND NOT EXISTS (SELECT 1 FROM customer_ledger_entries x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:customer_ledger_entries'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_customer_ledger_entries_no_delete",
        table = "customer_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_customer_ledger_entries_no_delete BEFORE DELETE ON customer_ledger_entries
                BEGIN SELECT RAISE(ABORT, 'guard:customer_ledger_entries_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_customer_ledger_entries_no_update",
        table = "customer_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_customer_ledger_entries_no_update BEFORE UPDATE ON customer_ledger_entries
                BEGIN SELECT RAISE(ABORT, 'guard:customer_ledger_entries_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_customers_insert",
        table = "customers",
        sql =
            """
                CREATE TRIGGER guard_customers_insert BEFORE INSERT ON customers
                WHEN NOT IFNULL((
                  (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80))
                  AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
                  AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
                  AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
                  AND typeof(NEW.balance) = 'integer'
                  AND typeof(NEW.points_balance) = 'integer' AND NEW.points_balance BETWEEN -1000000000000 AND 1000000000000
                  AND (NEW.anonymized_at IS NULL OR (typeof(NEW.anonymized_at) = 'integer'))
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.name IS NOT NULL OR NEW.anonymized_at IS NOT NULL)
                  AND (NEW.anonymized_at IS NULL OR (NEW.name IS NULL AND NEW.phone IS NULL AND NEW.email IS NULL AND NEW.notes IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:customers'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_customers_update",
        table = "customers",
        sql =
            """
                CREATE TRIGGER guard_customers_update BEFORE UPDATE ON customers
                WHEN NOT IFNULL((
                  (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80))
                  AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
                  AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
                  AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
                  AND typeof(NEW.balance) = 'integer'
                  AND typeof(NEW.points_balance) = 'integer' AND NEW.points_balance BETWEEN -1000000000000 AND 1000000000000
                  AND (NEW.anonymized_at IS NULL OR (typeof(NEW.anonymized_at) = 'integer'))
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.name IS NOT NULL OR NEW.anonymized_at IS NOT NULL)
                  AND (NEW.anonymized_at IS NULL OR (NEW.name IS NULL AND NEW.phone IS NULL AND NEW.email IS NULL AND NEW.notes IS NULL))
                  AND (OLD.anonymized_at IS NULL OR NEW.anonymized_at IS OLD.anonymized_at)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:customers'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_document_attachments_insert",
        table = "document_attachments",
        sql =
            """
                CREATE TRIGGER guard_document_attachments_insert BEFORE INSERT ON document_attachments
                WHEN NOT IFNULL((
                  (NEW.caption IS NULL OR (typeof(NEW.caption) = 'text' AND length(NEW.caption) BETWEEN 1 AND 120))
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.expense_id IS NULL) <> (NEW.purchase_id IS NULL)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:document_attachments'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_document_attachments_update",
        table = "document_attachments",
        sql =
            """
                CREATE TRIGGER guard_document_attachments_update BEFORE UPDATE ON document_attachments
                WHEN NOT IFNULL((
                  (NEW.caption IS NULL OR (typeof(NEW.caption) = 'text' AND length(NEW.caption) BETWEEN 1 AND 120))
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.expense_id IS NULL) <> (NEW.purchase_id IS NULL)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:document_attachments'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_expense_categories_insert",
        table = "expense_categories",
        sql =
            """
                CREATE TRIGGER guard_expense_categories_insert BEFORE INSERT ON expense_categories
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.is_system IN (0, 1)
                  AND NEW.is_fixed IN (0, 1)
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM expense_categories c WHERE c.normalized_name = NEW.normalized_name AND c.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:expense_categories'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_expense_categories_no_delete",
        table = "expense_categories",
        sql =
            """
                CREATE TRIGGER guard_expense_categories_no_delete BEFORE DELETE ON expense_categories
                WHEN OLD.is_system = 1
                BEGIN SELECT RAISE(ABORT, 'guard:expense_categories_system'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_expense_categories_update",
        table = "expense_categories",
        sql =
            """
                CREATE TRIGGER guard_expense_categories_update BEFORE UPDATE ON expense_categories
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.is_system IN (0, 1)
                  AND NEW.is_fixed IN (0, 1)
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.is_system = 0 OR NEW.archived_at IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM expense_categories c WHERE c.normalized_name = NEW.normalized_name AND c.archived_at IS NULL AND c.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:expense_categories'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_expenses_insert",
        table = "expenses",
        sql =
            """
                CREATE TRIGGER guard_expenses_insert BEFORE INSERT ON expenses
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
                  AND typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 1 AND 1000000000000000
                  AND (NEW.description IS NULL OR (typeof(NEW.description) = 'text' AND length(NEW.description) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM expenses x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:expenses'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_expenses_no_delete",
        table = "expenses",
        sql =
            """
                CREATE TRIGGER guard_expenses_no_delete BEFORE DELETE ON expenses
                BEGIN SELECT RAISE(ABORT, 'guard:expenses_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_expenses_void_only",
        table = "expenses",
        sql =
            """
                CREATE TRIGGER guard_expenses_void_only BEFORE UPDATE ON expenses
                WHEN NOT IFNULL((
                  OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
                  AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
                  AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
                  AND NEW.id IS OLD.id AND NEW.expense_category_id IS OLD.expense_category_id AND NEW.amount IS OLD.amount AND NEW.description IS OLD.description AND NEW.money_account_id IS OLD.money_account_id AND NEW.recurring_expense_id IS OLD.recurring_expense_id
                  AND NEW.occurred_at IS OLD.occurred_at AND NEW.created_at IS OLD.created_at AND NEW.business_date IS OLD.business_date
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:expenses_void_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_fx_rates_insert",
        table = "fx_rates",
        sql =
            """
                CREATE TRIGGER guard_fx_rates_insert BEFORE INSERT ON fx_rates
                WHEN NOT IFNULL((
                  length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
                  AND typeof(NEW.rate_micro) = 'integer' AND NEW.rate_micro BETWEEN 1 AND 1000000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND NOT EXISTS (SELECT 1 FROM fx_rates x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:fx_rates'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_fx_rates_no_delete",
        table = "fx_rates",
        sql =
            """
                CREATE TRIGGER guard_fx_rates_no_delete BEFORE DELETE ON fx_rates
                BEGIN SELECT RAISE(ABORT, 'guard:fx_rates_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_fx_rates_no_update",
        table = "fx_rates",
        sql =
            """
                CREATE TRIGGER guard_fx_rates_no_update BEFORE UPDATE ON fx_rates
                BEGIN SELECT RAISE(ABORT, 'guard:fx_rates_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_locations_insert",
        table = "locations",
        sql =
            """
                CREATE TRIGGER guard_locations_insert BEFORE INSERT ON locations
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('STORE', 'WAREHOUSE', 'STALL', 'OTHER')
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.system_key IS NOT 'MAIN' OR NEW.archived_at IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM locations o WHERE o.normalized_name = NEW.normalized_name AND o.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:locations'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_locations_no_delete",
        table = "locations",
        sql =
            """
                CREATE TRIGGER guard_locations_no_delete BEFORE DELETE ON locations
                WHEN OLD.system_key = 'MAIN'
                BEGIN SELECT RAISE(ABORT, 'guard:locations_system'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_locations_update",
        table = "locations",
        sql =
            """
                CREATE TRIGGER guard_locations_update BEFORE UPDATE ON locations
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('STORE', 'WAREHOUSE', 'STALL', 'OTHER')
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.system_key IS NOT 'MAIN' OR NEW.archived_at IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM locations o WHERE o.normalized_name = NEW.normalized_name AND o.archived_at IS NULL AND o.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:locations'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_low_stock_alerts_insert",
        table = "low_stock_alerts",
        sql =
            """
                CREATE TRIGGER guard_low_stock_alerts_insert BEFORE INSERT ON low_stock_alerts
                WHEN NOT IFNULL((
                  typeof(NEW.on_hand_milli_at_trigger) = 'integer'
                  AND typeof(NEW.min_stock_milli_at_trigger) = 'integer'
                  AND NEW.notification_status IN ('PENDING', 'SHOWN', 'SUPPRESSED', 'BLOCKED')
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND (NEW.resolved_at IS NULL OR (typeof(NEW.resolved_at) = 'integer'))
                  AND NEW.resolved_at IS NULL
                  AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.product_id AND p.stock_mode = 'TRACKED')
                  AND NOT EXISTS (SELECT 1 FROM low_stock_alerts a WHERE a.product_id = NEW.product_id AND a.resolved_at IS NULL)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:low_stock_alerts'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_low_stock_alerts_update",
        table = "low_stock_alerts",
        sql =
            """
                CREATE TRIGGER guard_low_stock_alerts_update BEFORE UPDATE ON low_stock_alerts
                WHEN NOT IFNULL((
                  typeof(NEW.on_hand_milli_at_trigger) = 'integer'
                  AND typeof(NEW.min_stock_milli_at_trigger) = 'integer'
                  AND NEW.notification_status IN ('PENDING', 'SHOWN', 'SUPPRESSED', 'BLOCKED')
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND (NEW.resolved_at IS NULL OR (typeof(NEW.resolved_at) = 'integer'))
                  AND NEW.id = OLD.id AND NEW.product_id = OLD.product_id
                  AND (OLD.resolved_at IS NULL OR NEW.resolved_at IS OLD.resolved_at)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:low_stock_alerts'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_loyalty_ledger_entries_insert",
        table = "loyalty_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_loyalty_ledger_entries_insert BEFORE INSERT ON loyalty_ledger_entries
                WHEN NOT IFNULL((
                  NEW.type IN ('EARN', 'REDEEM', 'ADJUSTMENT', 'ENTRY_VOID')
                  AND typeof(NEW.points) = 'integer' AND NEW.points BETWEEN -1000000000000 AND 1000000000000
                  AND typeof(NEW.balance_after) = 'integer' AND NEW.balance_after BETWEEN -1000000000000 AND 1000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND NEW.points <> 0
                  AND CASE NEW.type WHEN 'EARN' THEN NEW.points > 0 WHEN 'REDEEM' THEN NEW.points < 0 ELSE 1 END
                  AND (NEW.type IN ('EARN', 'REDEEM')) = (NEW.sale_id IS NOT NULL)
                  AND (NEW.type = 'ENTRY_VOID') = (NEW.reverses_entry_id IS NOT NULL)
                  AND (NEW.reverses_entry_id IS NULL OR EXISTS (SELECT 1 FROM loyalty_ledger_entries o WHERE o.id = NEW.reverses_entry_id AND o.customer_id = NEW.customer_id AND o.points = -NEW.points AND o.type <> 'ENTRY_VOID'))
                  AND NOT EXISTS (SELECT 1 FROM loyalty_ledger_entries e WHERE e.reverses_entry_id = NEW.reverses_entry_id)
                  AND NOT EXISTS (SELECT 1 FROM loyalty_ledger_entries x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:loyalty_ledger_entries'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_loyalty_ledger_entries_no_delete",
        table = "loyalty_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_loyalty_ledger_entries_no_delete BEFORE DELETE ON loyalty_ledger_entries
                BEGIN SELECT RAISE(ABORT, 'guard:loyalty_ledger_entries_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_loyalty_ledger_entries_no_update",
        table = "loyalty_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_loyalty_ledger_entries_no_update BEFORE UPDATE ON loyalty_ledger_entries
                BEGIN SELECT RAISE(ABORT, 'guard:loyalty_ledger_entries_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_media_assets_insert",
        table = "media_assets",
        sql =
            """
                CREATE TRIGGER guard_media_assets_insert BEFORE INSERT ON media_assets
                WHEN NOT IFNULL((
                  length(NEW.sha256) = 64 AND NEW.sha256 NOT GLOB '*[^0-9a-f]*'
                  AND NEW.kind IN ('PRODUCT_PHOTO', 'DOCUMENT_ATTACHMENT', 'PAYMENT_QR', 'BUSINESS_LOGO')
                  AND typeof(NEW.mime_type) = 'text' AND length(NEW.mime_type) BETWEEN 1 AND 40
                  AND typeof(NEW.width_px) = 'integer' AND NEW.width_px BETWEEN 1 AND 20000
                  AND typeof(NEW.height_px) = 'integer' AND NEW.height_px BETWEEN 1 AND 20000
                  AND typeof(NEW.size_bytes) = 'integer' AND NEW.size_bytes BETWEEN 1 AND 2097152
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.orphaned_at IS NULL OR (typeof(NEW.orphaned_at) = 'integer'))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:media_assets'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_media_assets_update",
        table = "media_assets",
        sql =
            """
                CREATE TRIGGER guard_media_assets_update BEFORE UPDATE ON media_assets
                WHEN NOT IFNULL((
                  length(NEW.sha256) = 64 AND NEW.sha256 NOT GLOB '*[^0-9a-f]*'
                  AND NEW.kind IN ('PRODUCT_PHOTO', 'DOCUMENT_ATTACHMENT', 'PAYMENT_QR', 'BUSINESS_LOGO')
                  AND typeof(NEW.mime_type) = 'text' AND length(NEW.mime_type) BETWEEN 1 AND 40
                  AND typeof(NEW.width_px) = 'integer' AND NEW.width_px BETWEEN 1 AND 20000
                  AND typeof(NEW.height_px) = 'integer' AND NEW.height_px BETWEEN 1 AND 20000
                  AND typeof(NEW.size_bytes) = 'integer' AND NEW.size_bytes BETWEEN 1 AND 2097152
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.orphaned_at IS NULL OR (typeof(NEW.orphaned_at) = 'integer'))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:media_assets'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_message_templates_insert",
        table = "message_templates",
        sql =
            """
                CREATE TRIGGER guard_message_templates_insert BEFORE INSERT ON message_templates
                WHEN NOT IFNULL((
                  NEW.template_key IN ('PAYMENT_REMINDER')
                  AND typeof(NEW.language_tag) = 'text' AND length(NEW.language_tag) BETWEEN 1 AND 8
                  AND typeof(NEW.body) = 'text' AND length(NEW.body) BETWEEN 1 AND 1000
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:message_templates'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_message_templates_update",
        table = "message_templates",
        sql =
            """
                CREATE TRIGGER guard_message_templates_update BEFORE UPDATE ON message_templates
                WHEN NOT IFNULL((
                  NEW.template_key IN ('PAYMENT_REMINDER')
                  AND typeof(NEW.language_tag) = 'text' AND length(NEW.language_tag) BETWEEN 1 AND 8
                  AND typeof(NEW.body) = 'text' AND length(NEW.body) BETWEEN 1 AND 1000
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:message_templates'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_money_accounts_insert",
        table = "money_accounts",
        sql =
            """
                CREATE TRIGGER guard_money_accounts_insert BEFORE INSERT ON money_accounts
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('CASH', 'BANK', 'WALLET', 'OTHER')
                  AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
                  AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
                  AND typeof(NEW.balance) = 'integer'
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM money_accounts a WHERE a.normalized_name = NEW.normalized_name AND a.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:money_accounts'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_money_accounts_update",
        table = "money_accounts",
        sql =
            """
                CREATE TRIGGER guard_money_accounts_update BEFORE UPDATE ON money_accounts
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('CASH', 'BANK', 'WALLET', 'OTHER')
                  AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
                  AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
                  AND typeof(NEW.balance) = 'integer'
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM money_accounts a WHERE a.normalized_name = NEW.normalized_name AND a.archived_at IS NULL AND a.id <> NEW.id))
                  AND ((NEW.currency_code = OLD.currency_code AND NEW.currency_decimals = OLD.currency_decimals) OR NOT EXISTS (SELECT 1 FROM money_movements m WHERE m.money_account_id = OLD.id) OR (NEW.currency_decimals = OLD.currency_decimals AND NEW.currency_code = (SELECT currency_code FROM app_settings WHERE id = 1) AND NEW.currency_decimals = (SELECT currency_decimals FROM app_settings WHERE id = 1) AND NEW.updated_at = (SELECT updated_at FROM app_settings WHERE id = 1)))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:money_accounts'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_money_movements_insert",
        table = "money_movements",
        sql =
            """
                CREATE TRIGGER guard_money_movements_insert BEFORE INSERT ON money_movements
                WHEN NOT IFNULL((
                  NEW.type IN ('OPENING_BALANCE', 'SALE_RECEIPT', 'CUSTOMER_PAYMENT', 'REFUND', 'FEE', 'EXPENSE_PAYMENT', 'PURCHASE_PAYMENT', 'SUPPLIER_PAYMENT', 'SUPPLIER_REFUND', 'OWNER_DRAW', 'OWNER_CONTRIBUTION', 'OTHER_INCOME', 'TRANSFER_OUT', 'TRANSFER_IN', 'CASH_DIFFERENCE', 'MOVEMENT_VOID')
                  AND typeof(NEW.amount) = 'integer'
                  AND typeof(NEW.balance_after) = 'integer'
                  AND (NEW.available_date IS NULL OR (date(NEW.available_date) = NEW.available_date))
                  AND (NEW.transfer_group_id IS NULL OR (typeof(NEW.transfer_group_id) = 'text' AND length(NEW.transfer_group_id) BETWEEN 36 AND 36))
                  AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
                  AND NEW.amount <> 0
                  AND CASE WHEN NEW.type IN ('SALE_RECEIPT', 'CUSTOMER_PAYMENT', 'SUPPLIER_REFUND', 'OWNER_CONTRIBUTION', 'OTHER_INCOME', 'TRANSFER_IN') THEN NEW.amount > 0 WHEN NEW.type IN ('REFUND', 'FEE', 'EXPENSE_PAYMENT', 'PURCHASE_PAYMENT', 'SUPPLIER_PAYMENT', 'OWNER_DRAW', 'TRANSFER_OUT') THEN NEW.amount < 0 ELSE 1 END
                  AND (NEW.type IN ('TRANSFER_OUT', 'TRANSFER_IN')) = (NEW.transfer_group_id IS NOT NULL)
                  AND (NEW.type NOT IN ('SALE_RECEIPT', 'FEE') OR NEW.sale_payment_id IS NOT NULL)
                  AND (NEW.type <> 'CUSTOMER_PAYMENT' OR NEW.customer_ledger_entry_id IS NOT NULL)
                  AND (NEW.type <> 'SUPPLIER_PAYMENT' OR NEW.supplier_ledger_entry_id IS NOT NULL)
                  AND (NEW.type <> 'EXPENSE_PAYMENT' OR NEW.expense_id IS NOT NULL)
                  AND (NEW.type <> 'PURCHASE_PAYMENT' OR NEW.purchase_id IS NOT NULL)
                  AND (NEW.type <> 'REFUND' OR NEW.sale_return_id IS NOT NULL)
                  AND (NEW.type <> 'SUPPLIER_REFUND' OR NEW.purchase_return_id IS NOT NULL)
                  AND (NEW.type <> 'CASH_DIFFERENCE' OR NEW.cash_session_id IS NOT NULL)
                  AND (NEW.type = 'MOVEMENT_VOID') = (NEW.reverses_movement_id IS NOT NULL)
                  AND (NEW.reverses_movement_id IS NULL OR EXISTS (SELECT 1 FROM money_movements o WHERE o.id = NEW.reverses_movement_id AND o.money_account_id = NEW.money_account_id AND o.amount = -NEW.amount AND o.type <> 'MOVEMENT_VOID'))
                  AND NOT EXISTS (SELECT 1 FROM money_movements m WHERE m.reverses_movement_id = NEW.reverses_movement_id)
                  AND NOT EXISTS (SELECT 1 FROM money_movements x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:money_movements'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_money_movements_no_delete",
        table = "money_movements",
        sql =
            """
                CREATE TRIGGER guard_money_movements_no_delete BEFORE DELETE ON money_movements
                BEGIN SELECT RAISE(ABORT, 'guard:money_movements_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_money_movements_no_update",
        table = "money_movements",
        sql =
            """
                CREATE TRIGGER guard_money_movements_no_update BEFORE UPDATE ON money_movements
                BEGIN SELECT RAISE(ABORT, 'guard:money_movements_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_order_lines_insert",
        table = "order_lines",
        sql =
            """
                CREATE TRIGGER guard_order_lines_insert BEFORE INSERT ON order_lines
                WHEN NOT IFNULL((
                  typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
                  AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
                  AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
                  AND NEW.line_total = NEW.net_amount + NEW.tax_amount
                  AND EXISTS (SELECT 1 FROM orders d WHERE d.id = NEW.order_id AND d.status IN ('PENDING', 'READY'))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:order_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_order_lines_no_delete",
        table = "order_lines",
        sql =
            """
                CREATE TRIGGER guard_order_lines_no_delete BEFORE DELETE ON order_lines
                WHEN EXISTS (SELECT 1 FROM orders d WHERE d.id = OLD.order_id AND d.status NOT IN ('PENDING', 'READY'))
                BEGIN SELECT RAISE(ABORT, 'guard:order_lines_closed'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_order_lines_update",
        table = "order_lines",
        sql =
            """
                CREATE TRIGGER guard_order_lines_update BEFORE UPDATE ON order_lines
                WHEN NOT IFNULL((
                  typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
                  AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
                  AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
                  AND NEW.line_total = NEW.net_amount + NEW.tax_amount
                  AND EXISTS (SELECT 1 FROM orders d WHERE d.id = NEW.order_id AND d.status IN ('PENDING', 'READY'))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:order_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_orders_insert",
        table = "orders",
        sql =
            """
                CREATE TRIGGER guard_orders_insert BEFORE INSERT ON orders
                WHEN NOT IFNULL((
                  NEW.status IN ('PENDING', 'READY', 'DELIVERED', 'CANCELLED')
                  AND (NEW.delivery_address IS NULL OR (typeof(NEW.delivery_address) = 'text' AND length(NEW.delivery_address) BETWEEN 1 AND 200))
                  AND (NEW.delivery_note IS NULL OR (typeof(NEW.delivery_note) = 'text' AND length(NEW.delivery_note) BETWEEN 1 AND 200))
                  AND typeof(NEW.shipping_amount) = 'integer' AND NEW.shipping_amount BETWEEN 0 AND 1000000000000000
                  AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
                  AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.status = 'DELIVERED') = (NEW.delivered_sale_id IS NOT NULL)
                  AND NEW.status = 'PENDING'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:orders'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_orders_update",
        table = "orders",
        sql =
            """
                CREATE TRIGGER guard_orders_update BEFORE UPDATE ON orders
                WHEN NOT IFNULL((
                  NEW.status IN ('PENDING', 'READY', 'DELIVERED', 'CANCELLED')
                  AND (NEW.delivery_address IS NULL OR (typeof(NEW.delivery_address) = 'text' AND length(NEW.delivery_address) BETWEEN 1 AND 200))
                  AND (NEW.delivery_note IS NULL OR (typeof(NEW.delivery_note) = 'text' AND length(NEW.delivery_note) BETWEEN 1 AND 200))
                  AND typeof(NEW.shipping_amount) = 'integer' AND NEW.shipping_amount BETWEEN 0 AND 1000000000000000
                  AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
                  AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.status = 'DELIVERED') = (NEW.delivered_sale_id IS NOT NULL)
                  AND (OLD.status IN ('PENDING', 'READY') OR (NEW.status = OLD.status AND NEW.delivered_sale_id IS OLD.delivered_sale_id AND NEW.customer_id IS OLD.customer_id AND (NEW.delivery_address IS OLD.delivery_address OR NEW.delivery_address IS NULL) AND (NEW.delivery_note IS OLD.delivery_note OR NEW.delivery_note IS NULL) AND NEW.shipping_amount = OLD.shipping_amount AND NEW.due_date IS OLD.due_date AND NEW.total = OLD.total AND NEW.occurred_at = OLD.occurred_at AND NEW.business_date = OLD.business_date AND NEW.created_at = OLD.created_at))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:orders'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_payment_methods_insert",
        table = "payment_methods",
        sql =
            """
                CREATE TRIGGER guard_payment_methods_insert BEFORE INSERT ON payment_methods
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('CASH', 'CARD', 'TRANSFER', 'WALLET', 'ON_ACCOUNT', 'OTHER')
                  AND typeof(NEW.fee_bp) = 'integer' AND NEW.fee_bp BETWEEN 0 AND 10000
                  AND typeof(NEW.fee_fixed) = 'integer' AND NEW.fee_fixed BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.settlement_days) = 'integer' AND NEW.settlement_days BETWEEN 0 AND 3650
                  AND typeof(NEW.price_adjustment_bp) = 'integer' AND NEW.price_adjustment_bp BETWEEN -10000 AND 10000
                  AND (NEW.instructions_text IS NULL OR (typeof(NEW.instructions_text) = 'text' AND length(NEW.instructions_text) BETWEEN 1 AND 500))
                  AND typeof(NEW.sort_order) = 'integer'
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM payment_methods p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:payment_methods'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_payment_methods_update",
        table = "payment_methods",
        sql =
            """
                CREATE TRIGGER guard_payment_methods_update BEFORE UPDATE ON payment_methods
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('CASH', 'CARD', 'TRANSFER', 'WALLET', 'ON_ACCOUNT', 'OTHER')
                  AND typeof(NEW.fee_bp) = 'integer' AND NEW.fee_bp BETWEEN 0 AND 10000
                  AND typeof(NEW.fee_fixed) = 'integer' AND NEW.fee_fixed BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.settlement_days) = 'integer' AND NEW.settlement_days BETWEEN 0 AND 3650
                  AND typeof(NEW.price_adjustment_bp) = 'integer' AND NEW.price_adjustment_bp BETWEEN -10000 AND 10000
                  AND (NEW.instructions_text IS NULL OR (typeof(NEW.instructions_text) = 'text' AND length(NEW.instructions_text) BETWEEN 1 AND 500))
                  AND typeof(NEW.sort_order) = 'integer'
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM payment_methods p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL AND p.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:payment_methods'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_price_index_entries_insert",
        table = "price_index_entries",
        sql =
            """
                CREATE TRIGGER guard_price_index_entries_insert BEFORE INSERT ON price_index_entries
                WHEN NOT IFNULL((
                  length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
                  AND typeof(NEW.change_bp) = 'integer' AND NEW.change_bp BETWEEN -10000 AND 1000000
                  AND (NEW.source_note IS NULL OR (typeof(NEW.source_note) = 'text' AND length(NEW.source_note) BETWEEN 1 AND 120))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:price_index_entries'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_price_index_entries_update",
        table = "price_index_entries",
        sql =
            """
                CREATE TRIGGER guard_price_index_entries_update BEFORE UPDATE ON price_index_entries
                WHEN NOT IFNULL((
                  length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
                  AND typeof(NEW.change_bp) = 'integer' AND NEW.change_bp BETWEEN -10000 AND 1000000
                  AND (NEW.source_note IS NULL OR (typeof(NEW.source_note) = 'text' AND length(NEW.source_note) BETWEEN 1 AND 120))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:price_index_entries'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_price_lists_insert",
        table = "price_lists",
        sql =
            """
                CREATE TRIGGER guard_price_lists_insert BEFORE INSERT ON price_lists
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('FIXED', 'RELATIVE')
                  AND (NEW.adjustment_bp IS NULL OR (typeof(NEW.adjustment_bp) = 'integer' AND NEW.adjustment_bp BETWEEN -10000 AND 1000000))
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.kind = 'RELATIVE') = (NEW.base_price_list_id IS NOT NULL)
                  AND (NEW.kind = 'RELATIVE') = (NEW.adjustment_bp IS NOT NULL)
                  AND (NEW.base_price_list_id IS NULL OR NEW.base_price_list_id <> NEW.id)
                  AND (NEW.system_key IS NOT 'DEFAULT' OR (NEW.kind = 'FIXED' AND NEW.archived_at IS NULL))
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM price_lists l WHERE l.normalized_name = NEW.normalized_name AND l.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:price_lists'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_price_lists_no_delete",
        table = "price_lists",
        sql =
            """
                CREATE TRIGGER guard_price_lists_no_delete BEFORE DELETE ON price_lists
                WHEN OLD.system_key = 'DEFAULT'
                BEGIN SELECT RAISE(ABORT, 'guard:price_lists_system'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_price_lists_update",
        table = "price_lists",
        sql =
            """
                CREATE TRIGGER guard_price_lists_update BEFORE UPDATE ON price_lists
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND NEW.kind IN ('FIXED', 'RELATIVE')
                  AND (NEW.adjustment_bp IS NULL OR (typeof(NEW.adjustment_bp) = 'integer' AND NEW.adjustment_bp BETWEEN -10000 AND 1000000))
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.kind = 'RELATIVE') = (NEW.base_price_list_id IS NOT NULL)
                  AND (NEW.kind = 'RELATIVE') = (NEW.adjustment_bp IS NOT NULL)
                  AND (NEW.base_price_list_id IS NULL OR NEW.base_price_list_id <> NEW.id)
                  AND (NEW.system_key IS NOT 'DEFAULT' OR (NEW.kind = 'FIXED' AND NEW.archived_at IS NULL))
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM price_lists l WHERE l.normalized_name = NEW.normalized_name AND l.archived_at IS NULL AND l.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:price_lists'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_components_insert",
        table = "product_components",
        sql =
            """
                CREATE TRIGGER guard_product_components_insert BEFORE INSERT ON product_components
                WHEN NOT IFNULL((
                  typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.parent_product_id <> NEW.component_product_id
                  AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.stock_mode IN ('COMPONENTS', 'TRACKED'))
                  AND NOT EXISTS (SELECT 1 FROM product_components a JOIN product_components b ON b.parent_product_id = a.component_product_id WHERE a.parent_product_id = NEW.component_product_id)
                  AND NOT (EXISTS (SELECT 1 FROM product_components a WHERE a.component_product_id = NEW.parent_product_id) AND EXISTS (SELECT 1 FROM product_components b WHERE b.parent_product_id = NEW.component_product_id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_components'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_components_update",
        table = "product_components",
        sql =
            """
                CREATE TRIGGER guard_product_components_update BEFORE UPDATE ON product_components
                WHEN NOT IFNULL((
                  typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.parent_product_id <> NEW.component_product_id
                  AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.stock_mode IN ('COMPONENTS', 'TRACKED'))
                  AND NOT EXISTS (SELECT 1 FROM product_components a JOIN product_components b ON b.parent_product_id = a.component_product_id WHERE a.parent_product_id = NEW.component_product_id)
                  AND NOT (EXISTS (SELECT 1 FROM product_components a WHERE a.component_product_id = NEW.parent_product_id) AND EXISTS (SELECT 1 FROM product_components b WHERE b.parent_product_id = NEW.component_product_id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_components'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_custom_values_insert",
        table = "product_custom_values",
        sql =
            """
                CREATE TRIGGER guard_product_custom_values_insert BEFORE INSERT ON product_custom_values
                WHEN NOT IFNULL((
                  typeof(NEW.value_text) = 'text' AND length(NEW.value_text) BETWEEN 1 AND 200
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_custom_values'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_custom_values_update",
        table = "product_custom_values",
        sql =
            """
                CREATE TRIGGER guard_product_custom_values_update BEFORE UPDATE ON product_custom_values
                WHEN NOT IFNULL((
                  typeof(NEW.value_text) = 'text' AND length(NEW.value_text) BETWEEN 1 AND 200
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_custom_values'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_modifiers_insert",
        table = "product_modifiers",
        sql =
            """
                CREATE TRIGGER guard_product_modifiers_insert BEFORE INSERT ON product_modifiers
                WHEN NOT IFNULL((
                  (NEW.group_name IS NULL OR (typeof(NEW.group_name) = 'text' AND length(NEW.group_name) BETWEEN 1 AND 40))
                  AND typeof(NEW.sort_order) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.product_id <> NEW.modifier_product_id
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_modifiers'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_modifiers_update",
        table = "product_modifiers",
        sql =
            """
                CREATE TRIGGER guard_product_modifiers_update BEFORE UPDATE ON product_modifiers
                WHEN NOT IFNULL((
                  (NEW.group_name IS NULL OR (typeof(NEW.group_name) = 'text' AND length(NEW.group_name) BETWEEN 1 AND 40))
                  AND typeof(NEW.sort_order) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.product_id <> NEW.modifier_product_id
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_modifiers'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_price_history_insert",
        table = "product_price_history",
        sql =
            """
                CREATE TRIGGER guard_product_price_history_insert BEFORE INSERT ON product_price_history
                WHEN NOT IFNULL((
                  (NEW.old_price IS NULL OR (typeof(NEW.old_price) = 'integer' AND NEW.old_price BETWEEN 0 AND 1000000000000))
                  AND typeof(NEW.new_price) = 'integer' AND NEW.new_price BETWEEN 0 AND 1000000000000
                  AND NEW.source IN ('INITIAL', 'MANUAL', 'BULK', 'BULK_UNDO', 'IMPORT')
                  AND (NEW.batch_id IS NULL OR (typeof(NEW.batch_id) = 'text' AND length(NEW.batch_id) BETWEEN 36 AND 36))
                  AND (NEW.bulk_mode IS NULL OR (NEW.bulk_mode IN ('PERCENT', 'AMOUNT', 'TARGET_MARGIN', 'REFERENCE_RATE')))
                  AND (NEW.percent_bp IS NULL OR (typeof(NEW.percent_bp) = 'integer' AND NEW.percent_bp BETWEEN -9999 AND 99999))
                  AND (NEW.amount_delta IS NULL OR (typeof(NEW.amount_delta) = 'integer' AND NEW.amount_delta BETWEEN -1000000000000 AND 1000000000000))
                  AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
                  AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
                  AND (NEW.rounding_step IS NULL OR (typeof(NEW.rounding_step) = 'integer' AND NEW.rounding_step BETWEEN 0 AND 1000000000000))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND (NEW.source <> 'INITIAL' OR NEW.old_price IS NULL)
                  AND (NEW.old_price IS NOT NULL OR NEW.source IN ('INITIAL', 'IMPORT'))
                  AND (NEW.source IN ('BULK', 'BULK_UNDO')) = (NEW.batch_id IS NOT NULL)
                  AND (NEW.source = 'BULK') = (NEW.bulk_mode IS NOT NULL)
                  AND (NEW.source = 'BULK') = (NEW.rounding_step IS NOT NULL)
                  AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'PERCENT') = (NEW.percent_bp IS NOT NULL)
                  AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'AMOUNT') = (NEW.amount_delta IS NOT NULL)
                  AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'TARGET_MARGIN') = (NEW.target_margin_bp IS NOT NULL)
                  AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'REFERENCE_RATE') = (NEW.fx_rate_micro IS NOT NULL)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_price_history'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_price_history_no_delete",
        table = "product_price_history",
        sql =
            """
                CREATE TRIGGER guard_product_price_history_no_delete BEFORE DELETE ON product_price_history
                WHEN EXISTS (SELECT 1 FROM products p WHERE p.id = OLD.product_id)
                BEGIN SELECT RAISE(ABORT, 'guard:product_price_history_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_price_history_no_update",
        table = "product_price_history",
        sql =
            """
                CREATE TRIGGER guard_product_price_history_no_update BEFORE UPDATE ON product_price_history
                BEGIN SELECT RAISE(ABORT, 'guard:product_price_history_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_prices_insert",
        table = "product_prices",
        sql =
            """
                CREATE TRIGGER guard_product_prices_insert BEFORE INSERT ON product_prices
                WHEN NOT IFNULL((
                  typeof(NEW.price) = 'integer' AND NEW.price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND EXISTS (SELECT 1 FROM price_lists l WHERE l.id = NEW.price_list_id AND l.kind = 'FIXED' AND l.system_key IS NOT 'DEFAULT')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_prices'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_prices_update",
        table = "product_prices",
        sql =
            """
                CREATE TRIGGER guard_product_prices_update BEFORE UPDATE ON product_prices
                WHEN NOT IFNULL((
                  typeof(NEW.price) = 'integer' AND NEW.price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND EXISTS (SELECT 1 FROM price_lists l WHERE l.id = NEW.price_list_id AND l.kind = 'FIXED' AND l.system_key IS NOT 'DEFAULT')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_prices'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_tags_insert",
        table = "product_tags",
        sql =
            """
                CREATE TRIGGER guard_product_tags_insert BEFORE INSERT ON product_tags
                WHEN NOT IFNULL((
                  typeof(NEW.product_id) = 'integer' AND typeof(NEW.tag_id) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_tags'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_tags_update",
        table = "product_tags",
        sql =
            """
                CREATE TRIGGER guard_product_tags_update BEFORE UPDATE ON product_tags
                WHEN NOT IFNULL((
                  typeof(NEW.product_id) = 'integer' AND typeof(NEW.tag_id) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_tags'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_units_insert",
        table = "product_units",
        sql =
            """
                CREATE TRIGGER guard_product_units_insert BEFORE INSERT ON product_units
                WHEN NOT IFNULL((
                  typeof(NEW.factor_milli) = 'integer' AND NEW.factor_milli BETWEEN 1 AND 1000000000
                  AND (NEW.sale_price IS NULL OR (typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000))
                  AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
                  AND NEW.for_sale IN (0, 1)
                  AND NEW.for_purchase IN (0, 1)
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL AND p.id <> NEW.product_id))
                  AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_units'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_product_units_update",
        table = "product_units",
        sql =
            """
                CREATE TRIGGER guard_product_units_update BEFORE UPDATE ON product_units
                WHEN NOT IFNULL((
                  typeof(NEW.factor_milli) = 'integer' AND NEW.factor_milli BETWEEN 1 AND 1000000000
                  AND (NEW.sale_price IS NULL OR (typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000))
                  AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
                  AND NEW.for_sale IN (0, 1)
                  AND NEW.for_purchase IN (0, 1)
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL AND p.id <> NEW.product_id))
                  AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode AND u.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:product_units'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_production_batch_lines_insert",
        table = "production_batch_lines",
        sql =
            """
                CREATE TRIGGER guard_production_batch_lines_insert BEFORE INSERT ON production_batch_lines
                WHEN NOT IFNULL((
                  typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
                  AND NOT EXISTS (SELECT 1 FROM production_batch_lines x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:production_batch_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_production_batch_lines_no_delete",
        table = "production_batch_lines",
        sql =
            """
                CREATE TRIGGER guard_production_batch_lines_no_delete BEFORE DELETE ON production_batch_lines
                BEGIN SELECT RAISE(ABORT, 'guard:production_batch_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_production_batch_lines_no_update",
        table = "production_batch_lines",
        sql =
            """
                CREATE TRIGGER guard_production_batch_lines_no_update BEFORE UPDATE ON production_batch_lines
                BEGIN SELECT RAISE(ABORT, 'guard:production_batch_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_production_batches_insert",
        table = "production_batches",
        sql =
            """
                CREATE TRIGGER guard_production_batches_insert BEFORE INSERT ON production_batches
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
                  AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.total_cost) = 'integer' AND NEW.total_cost BETWEEN 0 AND 1000000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND NOT EXISTS (SELECT 1 FROM production_batches x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:production_batches'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_production_batches_no_delete",
        table = "production_batches",
        sql =
            """
                CREATE TRIGGER guard_production_batches_no_delete BEFORE DELETE ON production_batches
                BEGIN SELECT RAISE(ABORT, 'guard:production_batches_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_production_batches_void_only",
        table = "production_batches",
        sql =
            """
                CREATE TRIGGER guard_production_batches_void_only BEFORE UPDATE ON production_batches
                WHEN NOT IFNULL((
                  OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
                  AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
                  AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
                  AND NEW.id IS OLD.id AND NEW.product_id IS OLD.product_id AND NEW.location_id IS OLD.location_id AND NEW.quantity_milli IS OLD.quantity_milli AND NEW.total_cost IS OLD.total_cost AND NEW.note IS OLD.note
                  AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date AND NEW.created_at IS OLD.created_at
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:production_batches_void_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_products_insert",
        table = "products",
        sql =
            """
                CREATE TRIGGER guard_products_insert BEFORE INSERT ON products
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
                  AND NEW.stock_mode IN ('TRACKED', 'UNTRACKED', 'COMPONENTS')
                  AND typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.average_cost) = 'integer' AND NEW.average_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.initial_cost) = 'integer' AND NEW.initial_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.fixed_unit_cost) = 'integer' AND NEW.fixed_unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.stock_value) = 'integer' AND NEW.stock_value BETWEEN 0 AND 9000000000000000
                  AND typeof(NEW.on_hand_milli) = 'integer'
                  AND typeof(NEW.min_stock_milli) = 'integer' AND NEW.min_stock_milli BETWEEN 0 AND 8000000000
                  AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
                  AND (NEW.variant_axis_1 IS NULL OR (typeof(NEW.variant_axis_1) = 'text' AND length(NEW.variant_axis_1) BETWEEN 1 AND 40))
                  AND (NEW.variant_axis_2 IS NULL OR (typeof(NEW.variant_axis_2) = 'text' AND length(NEW.variant_axis_2) BETWEEN 1 AND 40))
                  AND (NEW.variant_value_1 IS NULL OR (typeof(NEW.variant_value_1) = 'text' AND length(NEW.variant_value_1) BETWEEN 1 AND 40))
                  AND (NEW.variant_value_2 IS NULL OR (typeof(NEW.variant_value_2) = 'text' AND length(NEW.variant_value_2) BETWEEN 1 AND 40))
                  AND typeof(NEW.replacement_cost) = 'integer' AND NEW.replacement_cost BETWEEN 0 AND 1000000000000
                  AND (NEW.replacement_cost_at IS NULL OR (typeof(NEW.replacement_cost_at) = 'integer'))
                  AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
                  AND (NEW.reference_cost IS NULL OR (typeof(NEW.reference_cost) = 'integer' AND NEW.reference_cost BETWEEN 0 AND 1000000000000))
                  AND (NEW.reference_price IS NULL OR (typeof(NEW.reference_price) = 'integer' AND NEW.reference_price BETWEEN 0 AND 1000000000000))
                  AND (NEW.price_review_since IS NULL OR (typeof(NEW.price_review_since) = 'integer'))
                  AND NEW.tracks_lots IN (0, 1)
                  AND (NEW.expiry_notice_days IS NULL OR (typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500))
                  AND NEW.usage IN ('SALE', 'INPUT', 'BOTH')
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.stock_mode = 'TRACKED' OR (NEW.on_hand_milli = 0 AND NEW.stock_value = 0))
                  AND (NEW.stock_mode = 'UNTRACKED' OR NEW.fixed_unit_cost = 0)
                  AND (NEW.tracks_lots = 0 OR NEW.stock_mode = 'TRACKED')
                  AND (NEW.sale_price = 0 AND NEW.initial_cost = 0 AND NEW.fixed_unit_cost = 0 AND NEW.replacement_cost = 0 OR (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL)
                  AND (NEW.parent_product_id IS NULL OR EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.parent_product_id IS NULL AND p.id <> NEW.id))
                  AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode AND u.product_id <> NEW.id))
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL))
                  AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:products'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_products_update",
        table = "products",
        sql =
            """
                CREATE TRIGGER guard_products_update BEFORE UPDATE ON products
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
                  AND NEW.stock_mode IN ('TRACKED', 'UNTRACKED', 'COMPONENTS')
                  AND typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.average_cost) = 'integer' AND NEW.average_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.initial_cost) = 'integer' AND NEW.initial_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.fixed_unit_cost) = 'integer' AND NEW.fixed_unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.stock_value) = 'integer' AND NEW.stock_value BETWEEN 0 AND 9000000000000000
                  AND typeof(NEW.on_hand_milli) = 'integer'
                  AND typeof(NEW.min_stock_milli) = 'integer' AND NEW.min_stock_milli BETWEEN 0 AND 8000000000
                  AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
                  AND (NEW.variant_axis_1 IS NULL OR (typeof(NEW.variant_axis_1) = 'text' AND length(NEW.variant_axis_1) BETWEEN 1 AND 40))
                  AND (NEW.variant_axis_2 IS NULL OR (typeof(NEW.variant_axis_2) = 'text' AND length(NEW.variant_axis_2) BETWEEN 1 AND 40))
                  AND (NEW.variant_value_1 IS NULL OR (typeof(NEW.variant_value_1) = 'text' AND length(NEW.variant_value_1) BETWEEN 1 AND 40))
                  AND (NEW.variant_value_2 IS NULL OR (typeof(NEW.variant_value_2) = 'text' AND length(NEW.variant_value_2) BETWEEN 1 AND 40))
                  AND typeof(NEW.replacement_cost) = 'integer' AND NEW.replacement_cost BETWEEN 0 AND 1000000000000
                  AND (NEW.replacement_cost_at IS NULL OR (typeof(NEW.replacement_cost_at) = 'integer'))
                  AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
                  AND (NEW.reference_cost IS NULL OR (typeof(NEW.reference_cost) = 'integer' AND NEW.reference_cost BETWEEN 0 AND 1000000000000))
                  AND (NEW.reference_price IS NULL OR (typeof(NEW.reference_price) = 'integer' AND NEW.reference_price BETWEEN 0 AND 1000000000000))
                  AND (NEW.price_review_since IS NULL OR (typeof(NEW.price_review_since) = 'integer'))
                  AND NEW.tracks_lots IN (0, 1)
                  AND (NEW.expiry_notice_days IS NULL OR (typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500))
                  AND NEW.usage IN ('SALE', 'INPUT', 'BOTH')
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.stock_mode = 'TRACKED' OR (NEW.on_hand_milli = 0 AND NEW.stock_value = 0))
                  AND (NEW.stock_mode = 'UNTRACKED' OR NEW.fixed_unit_cost = 0)
                  AND (NEW.tracks_lots = 0 OR NEW.stock_mode = 'TRACKED')
                  AND (NEW.sale_price = 0 AND NEW.initial_cost = 0 AND NEW.fixed_unit_cost = 0 AND NEW.replacement_cost = 0 OR (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL)
                  AND (NEW.parent_product_id IS NULL OR EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.parent_product_id IS NULL AND p.id <> NEW.id))
                  AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode AND u.product_id <> NEW.id))
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL AND p.id <> NEW.id))
                  AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL AND p.id <> NEW.id))
                  AND ((NEW.base_unit_id = OLD.base_unit_id AND NEW.stock_mode = OLD.stock_mode AND NEW.tracks_lots = OLD.tracks_lots) OR (NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.product_id = OLD.id) AND NOT EXISTS (SELECT 1 FROM sale_lines l WHERE l.product_id = OLD.id) AND NOT EXISTS (SELECT 1 FROM sale_line_components c WHERE c.component_product_id = OLD.id)))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:products'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_lines_insert",
        table = "purchase_lines",
        sql =
            """
                CREATE TRIGGER guard_purchase_lines_insert BEFORE INSERT ON purchase_lines
                WHEN NOT IFNULL((
                  typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
                  AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
                  AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.cost_amount) = 'integer' AND NEW.cost_amount BETWEEN 0 AND 1000000000000000
                  AND (NEW.reference_unit_cost IS NULL OR (typeof(NEW.reference_unit_cost) = 'integer' AND NEW.reference_unit_cost BETWEEN 0 AND 1000000000000))
                  AND NOT EXISTS (SELECT 1 FROM purchase_lines x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_lines_no_delete",
        table = "purchase_lines",
        sql =
            """
                CREATE TRIGGER guard_purchase_lines_no_delete BEFORE DELETE ON purchase_lines
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_lines_no_update",
        table = "purchase_lines",
        sql =
            """
                CREATE TRIGGER guard_purchase_lines_no_update BEFORE UPDATE ON purchase_lines
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_return_lines_insert",
        table = "purchase_return_lines",
        sql =
            """
                CREATE TRIGGER guard_purchase_return_lines_insert BEFORE INSERT ON purchase_return_lines
                WHEN NOT IFNULL((
                  typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND NOT EXISTS (SELECT 1 FROM purchase_return_lines x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_return_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_return_lines_no_delete",
        table = "purchase_return_lines",
        sql =
            """
                CREATE TRIGGER guard_purchase_return_lines_no_delete BEFORE DELETE ON purchase_return_lines
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_return_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_return_lines_no_update",
        table = "purchase_return_lines",
        sql =
            """
                CREATE TRIGGER guard_purchase_return_lines_no_update BEFORE UPDATE ON purchase_return_lines
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_return_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_returns_insert",
        table = "purchase_returns",
        sql =
            """
                CREATE TRIGGER guard_purchase_returns_insert BEFORE INSERT ON purchase_returns
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
                  AND NEW.resolution IN ('CASH_REFUND', 'SUPPLIER_CREDIT')
                  AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.resolution <> 'SUPPLIER_CREDIT' OR NEW.supplier_id IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM purchase_returns x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_returns'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_returns_no_delete",
        table = "purchase_returns",
        sql =
            """
                CREATE TRIGGER guard_purchase_returns_no_delete BEFORE DELETE ON purchase_returns
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_returns_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchase_returns_void_only",
        table = "purchase_returns",
        sql =
            """
                CREATE TRIGGER guard_purchase_returns_void_only BEFORE UPDATE ON purchase_returns
                WHEN NOT IFNULL((
                  OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
                  AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
                  AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
                  AND NEW.id IS OLD.id AND NEW.purchase_id IS OLD.purchase_id AND NEW.supplier_id IS OLD.supplier_id AND NEW.resolution IS OLD.resolution AND NEW.total IS OLD.total AND NEW.money_account_id IS OLD.money_account_id
                  AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date AND NEW.created_at IS OLD.created_at
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:purchase_returns_void_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchases_insert",
        table = "purchases",
        sql =
            """
                CREATE TRIGGER guard_purchases_insert BEFORE INSERT ON purchases
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
                  AND (NEW.invoice_reference IS NULL OR (typeof(NEW.invoice_reference) = 'text' AND length(NEW.invoice_reference) BETWEEN 1 AND 40))
                  AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.cost_total) = 'integer' AND NEW.cost_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.amount_paid) = 'integer' AND NEW.amount_paid BETWEEN 0 AND 1000000000000000
                  AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
                  AND (NEW.fx_currency_code IS NULL OR (length(NEW.fx_currency_code) = 3 AND NEW.fx_currency_code = upper(NEW.fx_currency_code) AND NEW.fx_currency_code NOT GLOB '*[^A-Z]*'))
                  AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
                  AND NEW.total = NEW.net_total + NEW.tax_total
                  AND NEW.amount_paid <= NEW.total
                  AND (NEW.amount_paid = NEW.total OR NEW.supplier_id IS NOT NULL)
                  AND (NEW.fx_currency_code IS NULL) = (NEW.fx_rate_micro IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM purchases x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:purchases'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchases_no_delete",
        table = "purchases",
        sql =
            """
                CREATE TRIGGER guard_purchases_no_delete BEFORE DELETE ON purchases
                BEGIN SELECT RAISE(ABORT, 'guard:purchases_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_purchases_void_only",
        table = "purchases",
        sql =
            """
                CREATE TRIGGER guard_purchases_void_only BEFORE UPDATE ON purchases
                WHEN NOT IFNULL((
                  OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
                  AND NEW.void_reason IN ('ENTRY_ERROR', 'SUPPLIER_RETURN', 'UNDO', 'OTHER')
                  AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
                  AND NEW.id IS OLD.id AND NEW.supplier_id IS OLD.supplier_id AND NEW.invoice_reference IS OLD.invoice_reference AND NEW.net_total IS OLD.net_total AND NEW.tax_total IS OLD.tax_total AND NEW.total IS OLD.total
                  AND NEW.cost_total IS OLD.cost_total AND NEW.amount_paid IS OLD.amount_paid AND NEW.money_account_id IS OLD.money_account_id AND NEW.due_date IS OLD.due_date AND NEW.location_id IS OLD.location_id AND NEW.fx_currency_code IS OLD.fx_currency_code
                  AND NEW.fx_rate_micro IS OLD.fx_rate_micro AND NEW.occurred_at IS OLD.occurred_at AND NEW.created_at IS OLD.created_at AND NEW.business_date IS OLD.business_date
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:purchases_void_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_quote_lines_insert",
        table = "quote_lines",
        sql =
            """
                CREATE TRIGGER guard_quote_lines_insert BEFORE INSERT ON quote_lines
                WHEN NOT IFNULL((
                  typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
                  AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
                  AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
                  AND NEW.line_total = NEW.net_amount + NEW.tax_amount
                  AND EXISTS (SELECT 1 FROM quotes d WHERE d.id = NEW.quote_id AND d.status = 'OPEN')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:quote_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_quote_lines_no_delete",
        table = "quote_lines",
        sql =
            """
                CREATE TRIGGER guard_quote_lines_no_delete BEFORE DELETE ON quote_lines
                WHEN EXISTS (SELECT 1 FROM quotes d WHERE d.id = OLD.quote_id AND d.status <> 'OPEN')
                BEGIN SELECT RAISE(ABORT, 'guard:quote_lines_closed'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_quote_lines_update",
        table = "quote_lines",
        sql =
            """
                CREATE TRIGGER guard_quote_lines_update BEFORE UPDATE ON quote_lines
                WHEN NOT IFNULL((
                  typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
                  AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
                  AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
                  AND NEW.line_total = NEW.net_amount + NEW.tax_amount
                  AND EXISTS (SELECT 1 FROM quotes d WHERE d.id = NEW.quote_id AND d.status = 'OPEN')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:quote_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_quotes_insert",
        table = "quotes",
        sql =
            """
                CREATE TRIGGER guard_quotes_insert BEFORE INSERT ON quotes
                WHEN NOT IFNULL((
                  NEW.status IN ('OPEN', 'CONVERTED', 'CANCELLED')
                  AND (NEW.valid_until IS NULL OR (date(NEW.valid_until) = NEW.valid_until))
                  AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.total = NEW.net_total + NEW.tax_total
                  AND (NEW.status = 'CONVERTED') = (NEW.converted_sale_id IS NOT NULL)
                  AND NEW.status = 'OPEN'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:quotes'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_quotes_update",
        table = "quotes",
        sql =
            """
                CREATE TRIGGER guard_quotes_update BEFORE UPDATE ON quotes
                WHEN NOT IFNULL((
                  NEW.status IN ('OPEN', 'CONVERTED', 'CANCELLED')
                  AND (NEW.valid_until IS NULL OR (date(NEW.valid_until) = NEW.valid_until))
                  AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND NEW.total = NEW.net_total + NEW.tax_total
                  AND (NEW.status = 'CONVERTED') = (NEW.converted_sale_id IS NOT NULL)
                  AND (OLD.status = 'OPEN' OR (NEW.status = OLD.status AND NEW.converted_sale_id IS OLD.converted_sale_id AND NEW.customer_id IS OLD.customer_id AND NEW.valid_until IS OLD.valid_until AND NEW.net_total = OLD.net_total AND NEW.tax_total = OLD.tax_total AND NEW.total = OLD.total AND NEW.note IS OLD.note AND NEW.occurred_at = OLD.occurred_at AND NEW.business_date = OLD.business_date AND NEW.created_at = OLD.created_at))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:quotes'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_recurring_expenses_insert",
        table = "recurring_expenses",
        sql =
            """
                CREATE TRIGGER guard_recurring_expenses_insert BEFORE INSERT ON recurring_expenses
                WHEN NOT IFNULL((
                  typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 1 AND 1000000000000000
                  AND (NEW.description IS NULL OR (typeof(NEW.description) = 'text' AND length(NEW.description) BETWEEN 1 AND 200))
                  AND NEW.recurrence IN ('WEEKLY', 'MONTHLY', 'YEARLY')
                  AND typeof(NEW.day_of_period) = 'integer' AND NEW.day_of_period BETWEEN 1 AND 366
                  AND date(NEW.next_due_date) = NEW.next_due_date
                  AND NEW.reminder_enabled IN (0, 1)
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND CASE NEW.recurrence WHEN 'WEEKLY' THEN NEW.day_of_period <= 7 WHEN 'MONTHLY' THEN NEW.day_of_period <= 31 ELSE 1 END
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:recurring_expenses'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_recurring_expenses_update",
        table = "recurring_expenses",
        sql =
            """
                CREATE TRIGGER guard_recurring_expenses_update BEFORE UPDATE ON recurring_expenses
                WHEN NOT IFNULL((
                  typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 1 AND 1000000000000000
                  AND (NEW.description IS NULL OR (typeof(NEW.description) = 'text' AND length(NEW.description) BETWEEN 1 AND 200))
                  AND NEW.recurrence IN ('WEEKLY', 'MONTHLY', 'YEARLY')
                  AND typeof(NEW.day_of_period) = 'integer' AND NEW.day_of_period BETWEEN 1 AND 366
                  AND date(NEW.next_due_date) = NEW.next_due_date
                  AND NEW.reminder_enabled IN (0, 1)
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND CASE NEW.recurrence WHEN 'WEEKLY' THEN NEW.day_of_period <= 7 WHEN 'MONTHLY' THEN NEW.day_of_period <= 31 ELSE 1 END
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:recurring_expenses'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_draft_lines_insert",
        table = "sale_draft_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_draft_lines_insert BEFORE INSERT ON sale_draft_lines
                WHEN NOT IFNULL((
                  typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND (NEW.unit_price IS NULL OR (typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000))
                  AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_draft_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_draft_lines_update",
        table = "sale_draft_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_draft_lines_update BEFORE UPDATE ON sale_draft_lines
                WHEN NOT IFNULL((
                  typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND (NEW.unit_price IS NULL OR (typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000))
                  AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_draft_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_drafts_insert",
        table = "sale_drafts",
        sql =
            """
                CREATE TRIGGER guard_sale_drafts_insert BEFORE INSERT ON sale_drafts
                WHEN NOT IFNULL((
                  (NEW.label IS NULL OR (typeof(NEW.label) = 'text' AND length(NEW.label) BETWEEN 1 AND 40))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_drafts'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_drafts_update",
        table = "sale_drafts",
        sql =
            """
                CREATE TRIGGER guard_sale_drafts_update BEFORE UPDATE ON sale_drafts
                WHEN NOT IFNULL((
                  (NEW.label IS NULL OR (typeof(NEW.label) = 'text' AND length(NEW.label) BETWEEN 1 AND 40))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_drafts'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_fiscal_marks_insert",
        table = "sale_fiscal_marks",
        sql =
            """
                CREATE TRIGGER guard_sale_fiscal_marks_insert BEFORE INSERT ON sale_fiscal_marks
                WHEN NOT IFNULL((
                  NEW.status IN ('PENDING', 'RECORDED', 'NOT_NEEDED')
                  AND (NEW.external_reference IS NULL OR (typeof(NEW.external_reference) = 'text' AND length(NEW.external_reference) BETWEEN 1 AND 60))
                  AND (NEW.recorded_at IS NULL OR (typeof(NEW.recorded_at) = 'integer'))
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.status <> 'RECORDED' OR NEW.recorded_at IS NOT NULL)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_fiscal_marks'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_fiscal_marks_update",
        table = "sale_fiscal_marks",
        sql =
            """
                CREATE TRIGGER guard_sale_fiscal_marks_update BEFORE UPDATE ON sale_fiscal_marks
                WHEN NOT IFNULL((
                  NEW.status IN ('PENDING', 'RECORDED', 'NOT_NEEDED')
                  AND (NEW.external_reference IS NULL OR (typeof(NEW.external_reference) = 'text' AND length(NEW.external_reference) BETWEEN 1 AND 60))
                  AND (NEW.recorded_at IS NULL OR (typeof(NEW.recorded_at) = 'integer'))
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.status <> 'RECORDED' OR NEW.recorded_at IS NOT NULL)
                  AND NEW.sale_id = OLD.sale_id
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_fiscal_marks'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_line_components_insert",
        table = "sale_line_components",
        sql =
            """
                CREATE TRIGGER guard_sale_line_components_insert BEFORE INSERT ON sale_line_components
                WHEN NOT IFNULL((
                  typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND NEW.tracked IN (0, 1)
                  AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
                  AND EXISTS (SELECT 1 FROM sale_lines l WHERE l.id = NEW.sale_line_id AND l.stock_effect = 'COMPONENTS')
                  AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.component_product_id AND p.stock_mode <> 'COMPONENTS' AND (p.stock_mode = 'TRACKED') = (NEW.tracked = 1))
                  AND NOT EXISTS (SELECT 1 FROM sale_line_components x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_line_components'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_line_components_no_delete",
        table = "sale_line_components",
        sql =
            """
                CREATE TRIGGER guard_sale_line_components_no_delete BEFORE DELETE ON sale_line_components
                BEGIN SELECT RAISE(ABORT, 'guard:sale_line_components_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_line_components_no_update",
        table = "sale_line_components",
        sql =
            """
                CREATE TRIGGER guard_sale_line_components_no_update BEFORE UPDATE ON sale_line_components
                BEGIN SELECT RAISE(ABORT, 'guard:sale_line_components_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_lines_insert",
        table = "sale_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_lines_insert BEFORE INSERT ON sale_lines
                WHEN NOT IFNULL((
                  typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.order_adjustment_share) = 'integer' AND NEW.order_adjustment_share BETWEEN -1000000000000000 AND 1000000000000000
                  AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
                  AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
                  AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.unit_replacement_cost) = 'integer' AND NEW.unit_replacement_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.line_replacement_cost) = 'integer' AND NEW.line_replacement_cost BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
                  AND NEW.stock_effect IN ('DIRECT', 'NONE', 'COMPONENTS')
                  AND NEW.line_total = NEW.net_amount + NEW.tax_amount
                  AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.product_id AND ((NEW.stock_effect = 'DIRECT' AND p.stock_mode = 'TRACKED') OR (NEW.stock_effect = 'NONE' AND p.stock_mode = 'UNTRACKED') OR (NEW.stock_effect = 'COMPONENTS' AND p.stock_mode = 'COMPONENTS')))
                  AND (NEW.parent_line_id IS NULL OR EXISTS (SELECT 1 FROM sale_lines pl WHERE pl.id = NEW.parent_line_id AND pl.sale_id = NEW.sale_id AND pl.parent_line_id IS NULL))
                  AND NOT EXISTS (SELECT 1 FROM sale_lines x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_lines_no_delete",
        table = "sale_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_lines_no_delete BEFORE DELETE ON sale_lines
                BEGIN SELECT RAISE(ABORT, 'guard:sale_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_lines_no_update",
        table = "sale_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_lines_no_update BEFORE UPDATE ON sale_lines
                BEGIN SELECT RAISE(ABORT, 'guard:sale_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_payments_insert",
        table = "sale_payments",
        sql =
            """
                CREATE TRIGGER guard_sale_payments_insert BEFORE INSERT ON sale_payments
                WHEN NOT IFNULL((
                  typeof(NEW.method_name_snapshot) = 'text' AND length(NEW.method_name_snapshot) BETWEEN 1 AND 40
                  AND NEW.method_kind_snapshot IN ('CASH', 'CARD', 'TRANSFER', 'WALLET', 'ON_ACCOUNT', 'OTHER')
                  AND typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.fee_amount) = 'integer' AND NEW.fee_amount BETWEEN 0 AND 1000000000000000
                  AND date(NEW.settlement_date) = NEW.settlement_date
                  AND (NEW.tendered_amount IS NULL OR (typeof(NEW.tendered_amount) = 'integer' AND NEW.tendered_amount BETWEEN 0 AND 1000000000000000))
                  AND (NEW.change_amount IS NULL OR (typeof(NEW.change_amount) = 'integer' AND NEW.change_amount BETWEEN 0 AND 1000000000000000))
                  AND (NEW.tender_currency_code IS NULL OR (length(NEW.tender_currency_code) = 3 AND NEW.tender_currency_code = upper(NEW.tender_currency_code) AND NEW.tender_currency_code NOT GLOB '*[^A-Z]*'))
                  AND (NEW.tender_currency_decimals IS NULL OR (typeof(NEW.tender_currency_decimals) = 'integer' AND NEW.tender_currency_decimals BETWEEN 0 AND 4))
                  AND (NEW.tender_amount IS NULL OR (typeof(NEW.tender_amount) = 'integer' AND NEW.tender_amount BETWEEN 0 AND 1000000000000000))
                  AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.tendered_amount IS NULL) = (NEW.change_amount IS NULL)
                  AND (NEW.tendered_amount IS NULL OR (NEW.method_kind_snapshot = 'CASH' AND NEW.tendered_amount - NEW.change_amount = NEW.amount))
                  AND (NEW.tender_currency_code IS NULL) = (NEW.tender_currency_decimals IS NULL)
                  AND (NEW.tender_currency_code IS NULL) = (NEW.tender_amount IS NULL)
                  AND (NEW.tender_currency_code IS NULL) = (NEW.fx_rate_micro IS NULL)
                  AND (NEW.method_kind_snapshot <> 'ON_ACCOUNT' OR (NEW.money_account_id IS NULL AND EXISTS (SELECT 1 FROM sales s WHERE s.id = NEW.sale_id AND s.customer_id IS NOT NULL)))
                  AND NOT EXISTS (SELECT 1 FROM sale_payments x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_payments'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_payments_no_delete",
        table = "sale_payments",
        sql =
            """
                CREATE TRIGGER guard_sale_payments_no_delete BEFORE DELETE ON sale_payments
                BEGIN SELECT RAISE(ABORT, 'guard:sale_payments_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_payments_no_update",
        table = "sale_payments",
        sql =
            """
                CREATE TRIGGER guard_sale_payments_no_update BEFORE UPDATE ON sale_payments
                BEGIN SELECT RAISE(ABORT, 'guard:sale_payments_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_return_lines_insert",
        table = "sale_return_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_return_lines_insert BEFORE INSERT ON sale_return_lines
                WHEN NOT IFNULL((
                  typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
                  AND NEW.restock IN (0, 1)
                  AND NEW.line_total = NEW.net_amount + NEW.tax_amount
                  AND (SELECT COALESCE(SUM(rl.quantity_milli), 0) FROM sale_return_lines rl JOIN sale_returns r ON r.id = rl.sale_return_id WHERE rl.sale_line_id = NEW.sale_line_id AND r.status = 'ACTIVE') + NEW.quantity_milli <= (SELECT sl.quantity_milli FROM sale_lines sl WHERE sl.id = NEW.sale_line_id)
                  AND NOT EXISTS (SELECT 1 FROM sale_return_lines x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_return_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_return_lines_no_delete",
        table = "sale_return_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_return_lines_no_delete BEFORE DELETE ON sale_return_lines
                BEGIN SELECT RAISE(ABORT, 'guard:sale_return_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_return_lines_no_update",
        table = "sale_return_lines",
        sql =
            """
                CREATE TRIGGER guard_sale_return_lines_no_update BEFORE UPDATE ON sale_return_lines
                BEGIN SELECT RAISE(ABORT, 'guard:sale_return_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_returns_insert",
        table = "sale_returns",
        sql =
            """
                CREATE TRIGGER guard_sale_returns_insert BEFORE INSERT ON sale_returns
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
                  AND NEW.resolution IN ('REFUND', 'STORE_CREDIT', 'EXCHANGE')
                  AND typeof(NEW.net_refund) = 'integer' AND NEW.net_refund BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_refund) = 'integer' AND NEW.tax_refund BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.total_refund) = 'integer' AND NEW.total_refund BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.cost_returned) = 'integer' AND NEW.cost_returned BETWEEN 0 AND 1000000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND NEW.total_refund = NEW.net_refund + NEW.tax_refund
                  AND EXISTS (SELECT 1 FROM sales s WHERE s.id = NEW.sale_id AND s.status = 'ACTIVE')
                  AND NOT EXISTS (SELECT 1 FROM sale_returns x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_returns'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_returns_no_delete",
        table = "sale_returns",
        sql =
            """
                CREATE TRIGGER guard_sale_returns_no_delete BEFORE DELETE ON sale_returns
                BEGIN SELECT RAISE(ABORT, 'guard:sale_returns_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sale_returns_void_only",
        table = "sale_returns",
        sql =
            """
                CREATE TRIGGER guard_sale_returns_void_only BEFORE UPDATE ON sale_returns
                WHEN NOT IFNULL((
                  OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
                  AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
                  AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
                  AND NEW.id IS OLD.id AND NEW.sale_id IS OLD.sale_id AND NEW.resolution IS OLD.resolution AND NEW.net_refund IS OLD.net_refund AND NEW.tax_refund IS OLD.tax_refund AND NEW.total_refund IS OLD.total_refund
                  AND NEW.cost_returned IS OLD.cost_returned AND NEW.money_account_id IS OLD.money_account_id AND NEW.replacement_sale_id IS OLD.replacement_sale_id AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date
                  AND NEW.created_at IS OLD.created_at
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sale_returns_void_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sales_insert",
        table = "sales",
        sql =
            """
                CREATE TRIGGER guard_sales_insert BEFORE INSERT ON sales
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
                  AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.total_cost) = 'integer' AND NEW.total_cost BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.total_replacement_cost) = 'integer' AND NEW.total_replacement_cost BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.order_adjustment) = 'integer' AND NEW.order_adjustment BETWEEN -1000000000000000 AND 1000000000000000
                  AND typeof(NEW.tip_amount) = 'integer' AND NEW.tip_amount BETWEEN 0 AND 1000000000000000
                  AND typeof(NEW.cash_rounding_amount) = 'integer' AND NEW.cash_rounding_amount BETWEEN -1000000000000 AND 1000000000000
                  AND typeof(NEW.amount_due) = 'integer' AND NEW.amount_due BETWEEN 0 AND 1000000000000000
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
                  AND NEW.total = NEW.net_total + NEW.tax_total
                  AND NEW.amount_due = NEW.total + NEW.tip_amount + NEW.cash_rounding_amount
                  AND NOT EXISTS (SELECT 1 FROM sales x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sales'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sales_no_delete",
        table = "sales",
        sql =
            """
                CREATE TRIGGER guard_sales_no_delete BEFORE DELETE ON sales
                BEGIN SELECT RAISE(ABORT, 'guard:sales_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_sales_void_only",
        table = "sales",
        sql =
            """
                CREATE TRIGGER guard_sales_void_only BEFORE UPDATE ON sales
                WHEN NOT IFNULL((
                  OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
                  AND NEW.void_reason IN ('ENTRY_ERROR', 'CUSTOMER_RETURN', 'UNDO', 'OTHER')
                  AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
                  AND NEW.id IS OLD.id AND NEW.net_total IS OLD.net_total AND NEW.tax_total IS OLD.tax_total AND NEW.total IS OLD.total AND NEW.total_cost IS OLD.total_cost AND NEW.total_replacement_cost IS OLD.total_replacement_cost
                  AND NEW.order_adjustment IS OLD.order_adjustment AND NEW.tip_amount IS OLD.tip_amount AND NEW.cash_rounding_amount IS OLD.cash_rounding_amount AND NEW.amount_due IS OLD.amount_due AND NEW.customer_id IS OLD.customer_id AND NEW.price_list_id IS OLD.price_list_id
                  AND NEW.location_id IS OLD.location_id AND NEW.cash_session_id IS OLD.cash_session_id AND NEW.quote_id IS OLD.quote_id AND NEW.order_id IS OLD.order_id AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at
                  AND NEW.created_at IS OLD.created_at AND NEW.business_date IS OLD.business_date
                  AND NOT EXISTS (SELECT 1 FROM sale_returns r WHERE r.sale_id = NEW.id AND r.status = 'ACTIVE')
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:sales_void_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_balances_insert",
        table = "stock_balances",
        sql =
            """
                CREATE TRIGGER guard_stock_balances_insert BEFORE INSERT ON stock_balances
                WHEN NOT IFNULL((
                  typeof(NEW.lot_key) = 'integer' AND NEW.lot_key >= 0
                  AND typeof(NEW.on_hand_milli) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_balances'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_balances_update",
        table = "stock_balances",
        sql =
            """
                CREATE TRIGGER guard_stock_balances_update BEFORE UPDATE ON stock_balances
                WHEN NOT IFNULL((
                  typeof(NEW.lot_key) = 'integer' AND NEW.lot_key >= 0
                  AND typeof(NEW.on_hand_milli) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_balances'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_lots_insert",
        table = "stock_lots",
        sql =
            """
                CREATE TRIGGER guard_stock_lots_insert BEFORE INSERT ON stock_lots
                WHEN NOT IFNULL((
                  (NEW.lot_code IS NULL OR (typeof(NEW.lot_code) = 'text' AND length(NEW.lot_code) BETWEEN 1 AND 60))
                  AND NEW.is_unassigned IN (0, 1)
                  AND (NEW.expiry_date IS NULL OR (date(NEW.expiry_date) = NEW.expiry_date))
                  AND (NEW.expiry_notified_at IS NULL OR (typeof(NEW.expiry_notified_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
                  AND (NEW.is_unassigned = 0 OR (NEW.lot_code IS NULL AND NEW.expiry_date IS NULL))
                  AND (NEW.is_unassigned = 0 OR NOT EXISTS (SELECT 1 FROM stock_lots l WHERE l.product_id = NEW.product_id AND l.is_unassigned = 1))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_lots'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_lots_update",
        table = "stock_lots",
        sql =
            """
                CREATE TRIGGER guard_stock_lots_update BEFORE UPDATE ON stock_lots
                WHEN NOT IFNULL((
                  (NEW.lot_code IS NULL OR (typeof(NEW.lot_code) = 'text' AND length(NEW.lot_code) BETWEEN 1 AND 60))
                  AND NEW.is_unassigned IN (0, 1)
                  AND (NEW.expiry_date IS NULL OR (date(NEW.expiry_date) = NEW.expiry_date))
                  AND (NEW.expiry_notified_at IS NULL OR (typeof(NEW.expiry_notified_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
                  AND (NEW.is_unassigned = 0 OR (NEW.lot_code IS NULL AND NEW.expiry_date IS NULL))
                  AND NEW.product_id = OLD.product_id AND NEW.is_unassigned = OLD.is_unassigned
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_lots'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_movements_insert",
        table = "stock_movements",
        sql =
            """
                CREATE TRIGGER guard_stock_movements_insert BEFORE INSERT ON stock_movements
                WHEN NOT IFNULL((
                  NEW.type IN ('OPENING_BALANCE', 'PURCHASE', 'SALE', 'ADJUSTMENT', 'COST_CORRECTION', 'PURCHASE_VOID', 'SALE_VOID', 'ADJUSTMENT_VOID', 'SALE_RETURN', 'SALE_RETURN_VOID', 'PURCHASE_RETURN', 'PURCHASE_RETURN_VOID', 'PRODUCTION_OUT', 'PRODUCTION_IN', 'PRODUCTION_VOID', 'TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_VOID')
                  AND (NEW.reason IS NULL OR (NEW.reason IN ('COUNT_CORRECTION', 'DAMAGED', 'EXPIRED', 'LOST_OR_STOLEN', 'OWN_USE', 'GIFT_OR_SAMPLE', 'OTHER')))
                  AND typeof(NEW.quantity_milli) = 'integer'
                  AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.total_cost) = 'integer'
                  AND typeof(NEW.average_cost_after) = 'integer' AND NEW.average_cost_after BETWEEN 0 AND 1000000000000
                  AND typeof(NEW.stock_value_after) = 'integer' AND NEW.stock_value_after BETWEEN 0 AND 9000000000000000
                  AND typeof(NEW.balance_after_milli) = 'integer'
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND typeof(NEW.created_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.product_id AND p.stock_mode = 'TRACKED' AND (NEW.quantity_milli = 0 OR (p.tracks_lots = 1) = (NEW.lot_id IS NOT NULL)))
                  AND (NEW.lot_id IS NULL OR EXISTS (SELECT 1 FROM stock_lots l WHERE l.id = NEW.lot_id AND l.product_id = NEW.product_id))
                  AND CASE
                        WHEN NEW.type IN ('OPENING_BALANCE', 'PURCHASE', 'SALE_VOID', 'SALE_RETURN', 'PURCHASE_RETURN_VOID', 'PRODUCTION_IN', 'TRANSFER_IN') THEN NEW.quantity_milli > 0
                        WHEN NEW.type IN ('SALE', 'PURCHASE_VOID', 'SALE_RETURN_VOID', 'PURCHASE_RETURN', 'PRODUCTION_OUT', 'TRANSFER_OUT') THEN NEW.quantity_milli < 0
                        WHEN NEW.type = 'COST_CORRECTION' THEN NEW.quantity_milli = 0 AND NEW.total_cost = 0
                        ELSE NEW.quantity_milli <> 0
                      END
                  AND (NEW.type NOT IN ('TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_VOID') OR NEW.total_cost = 0)
                  AND (NEW.type IN ('SALE', 'SALE_VOID')) = (NEW.sale_line_id IS NOT NULL)
                  AND (NEW.type IN ('PURCHASE', 'PURCHASE_VOID')) = (NEW.purchase_line_id IS NOT NULL)
                  AND (NEW.type IN ('SALE_RETURN', 'SALE_RETURN_VOID')) = (NEW.sale_return_line_id IS NOT NULL)
                  AND (NEW.type IN ('PURCHASE_RETURN', 'PURCHASE_RETURN_VOID')) = (NEW.purchase_return_line_id IS NOT NULL)
                  AND (NEW.type IN ('TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_VOID')) = (NEW.transfer_line_id IS NOT NULL)
                  AND (NEW.type IN ('PRODUCTION_OUT', 'PRODUCTION_IN', 'PRODUCTION_VOID')) = (NEW.production_batch_id IS NOT NULL)
                  AND (NEW.count_session_id IS NULL OR (NEW.type = 'ADJUSTMENT' AND NEW.reason = 'COUNT_CORRECTION'))
                  AND (NEW.sale_line_component_id IS NULL OR NEW.type IN ('SALE', 'SALE_VOID', 'SALE_RETURN', 'SALE_RETURN_VOID'))
                  AND (NEW.type IN ('PURCHASE_VOID', 'SALE_VOID', 'ADJUSTMENT_VOID', 'SALE_RETURN_VOID', 'PURCHASE_RETURN_VOID', 'PRODUCTION_VOID', 'TRANSFER_VOID')) = (NEW.reverses_movement_id IS NOT NULL)
                  AND (NEW.type = 'ADJUSTMENT') = (NEW.reason IS NOT NULL)
                  AND (NEW.reason IS NULL OR NEW.reason IN ('COUNT_CORRECTION', 'OTHER') OR NEW.quantity_milli < 0)
                  AND (NEW.reason IS NOT 'OTHER' OR NEW.note IS NOT NULL)
                  AND (NEW.reverses_movement_id IS NULL OR EXISTS (
                        SELECT 1 FROM stock_movements o
                        WHERE o.id = NEW.reverses_movement_id AND o.product_id = NEW.product_id AND o.location_id = NEW.location_id
                          AND o.lot_id IS NEW.lot_id AND o.quantity_milli = -NEW.quantity_milli
                          AND ((NEW.type = 'SALE_VOID' AND o.type = 'SALE')
                            OR (NEW.type = 'PURCHASE_VOID' AND o.type = 'PURCHASE')
                            OR (NEW.type = 'ADJUSTMENT_VOID' AND o.type IN ('ADJUSTMENT', 'OPENING_BALANCE'))
                            OR (NEW.type = 'SALE_RETURN_VOID' AND o.type = 'SALE_RETURN')
                            OR (NEW.type = 'PURCHASE_RETURN_VOID' AND o.type = 'PURCHASE_RETURN')
                            OR (NEW.type = 'PRODUCTION_VOID' AND o.type IN ('PRODUCTION_OUT', 'PRODUCTION_IN'))
                            OR (NEW.type = 'TRANSFER_VOID' AND o.type IN ('TRANSFER_OUT', 'TRANSFER_IN')))
                      ))
                  AND NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.reverses_movement_id = NEW.reverses_movement_id)
                  AND NOT EXISTS (SELECT 1 FROM stock_movements x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_movements'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_movements_no_delete",
        table = "stock_movements",
        sql =
            """
                CREATE TRIGGER guard_stock_movements_no_delete BEFORE DELETE ON stock_movements
                BEGIN SELECT RAISE(ABORT, 'guard:stock_movements_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_movements_no_update",
        table = "stock_movements",
        sql =
            """
                CREATE TRIGGER guard_stock_movements_no_update BEFORE UPDATE ON stock_movements
                BEGIN SELECT RAISE(ABORT, 'guard:stock_movements_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_transfer_lines_insert",
        table = "stock_transfer_lines",
        sql =
            """
                CREATE TRIGGER guard_stock_transfer_lines_insert BEFORE INSERT ON stock_transfer_lines
                WHEN NOT IFNULL((
                  typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
                  AND NOT EXISTS (SELECT 1 FROM stock_transfer_lines x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_transfer_lines'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_transfer_lines_no_delete",
        table = "stock_transfer_lines",
        sql =
            """
                CREATE TRIGGER guard_stock_transfer_lines_no_delete BEFORE DELETE ON stock_transfer_lines
                BEGIN SELECT RAISE(ABORT, 'guard:stock_transfer_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_transfer_lines_no_update",
        table = "stock_transfer_lines",
        sql =
            """
                CREATE TRIGGER guard_stock_transfer_lines_no_update BEFORE UPDATE ON stock_transfer_lines
                BEGIN SELECT RAISE(ABORT, 'guard:stock_transfer_lines_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_transfers_insert",
        table = "stock_transfers",
        sql =
            """
                CREATE TRIGGER guard_stock_transfers_insert BEFORE INSERT ON stock_transfers
                WHEN NOT IFNULL((
                  NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND NEW.from_location_id <> NEW.to_location_id
                  AND NOT EXISTS (SELECT 1 FROM stock_transfers x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_transfers'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_transfers_no_delete",
        table = "stock_transfers",
        sql =
            """
                CREATE TRIGGER guard_stock_transfers_no_delete BEFORE DELETE ON stock_transfers
                BEGIN SELECT RAISE(ABORT, 'guard:stock_transfers_no_delete'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_stock_transfers_void_only",
        table = "stock_transfers",
        sql =
            """
                CREATE TRIGGER guard_stock_transfers_void_only BEFORE UPDATE ON stock_transfers
                WHEN NOT IFNULL((
                  OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
                  AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
                  AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
                  AND NEW.id IS OLD.id AND NEW.from_location_id IS OLD.from_location_id AND NEW.to_location_id IS OLD.to_location_id AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date
                  AND NEW.created_at IS OLD.created_at
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:stock_transfers_void_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_supplier_ledger_entries_insert",
        table = "supplier_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_supplier_ledger_entries_insert BEFORE INSERT ON supplier_ledger_entries
                WHEN NOT IFNULL((
                  NEW.type IN ('OPENING_BALANCE', 'CREDIT_PURCHASE', 'PAYMENT', 'RETURN_CREDIT', 'ADJUSTMENT', 'ENTRY_VOID')
                  AND typeof(NEW.amount) = 'integer'
                  AND typeof(NEW.balance_after) = 'integer'
                  AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
                  AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
                  AND typeof(NEW.occurred_at) = 'integer'
                  AND date(NEW.business_date) = NEW.business_date
                  AND typeof(NEW.created_at) = 'integer'
                  AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
                  AND NEW.amount <> 0
                  AND CASE NEW.type WHEN 'CREDIT_PURCHASE' THEN NEW.amount > 0 WHEN 'PAYMENT' THEN NEW.amount < 0 WHEN 'RETURN_CREDIT' THEN NEW.amount < 0 ELSE 1 END
                  AND (NEW.type = 'CREDIT_PURCHASE') = (NEW.purchase_id IS NOT NULL)
                  AND (NEW.type = 'RETURN_CREDIT') = (NEW.purchase_return_id IS NOT NULL)
                  AND (NEW.type = 'ENTRY_VOID') = (NEW.reverses_entry_id IS NOT NULL)
                  AND (NEW.type <> 'ADJUSTMENT' OR NEW.note IS NOT NULL)
                  AND (NEW.reverses_entry_id IS NULL OR EXISTS (SELECT 1 FROM supplier_ledger_entries o WHERE o.id = NEW.reverses_entry_id AND o.supplier_id = NEW.supplier_id AND o.amount = -NEW.amount AND o.type <> 'ENTRY_VOID'))
                  AND NOT EXISTS (SELECT 1 FROM supplier_ledger_entries e WHERE e.reverses_entry_id = NEW.reverses_entry_id)
                  AND NOT EXISTS (SELECT 1 FROM supplier_ledger_entries x WHERE x.id = NEW.id)
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:supplier_ledger_entries'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_supplier_ledger_entries_no_delete",
        table = "supplier_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_supplier_ledger_entries_no_delete BEFORE DELETE ON supplier_ledger_entries
                BEGIN SELECT RAISE(ABORT, 'guard:supplier_ledger_entries_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_supplier_ledger_entries_no_update",
        table = "supplier_ledger_entries",
        sql =
            """
                CREATE TRIGGER guard_supplier_ledger_entries_no_update BEFORE UPDATE ON supplier_ledger_entries
                BEGIN SELECT RAISE(ABORT, 'guard:supplier_ledger_entries_append_only'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_suppliers_insert",
        table = "suppliers",
        sql =
            """
                CREATE TRIGGER guard_suppliers_insert BEFORE INSERT ON suppliers
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
                  AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
                  AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
                  AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
                  AND typeof(NEW.balance) = 'integer'
                  AND (NEW.payment_terms_days IS NULL OR (typeof(NEW.payment_terms_days) = 'integer' AND NEW.payment_terms_days BETWEEN 0 AND 3650))
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM suppliers s WHERE s.normalized_name = NEW.normalized_name AND s.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:suppliers'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_suppliers_update",
        table = "suppliers",
        sql =
            """
                CREATE TRIGGER guard_suppliers_update BEFORE UPDATE ON suppliers
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
                  AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
                  AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
                  AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
                  AND typeof(NEW.balance) = 'integer'
                  AND (NEW.payment_terms_days IS NULL OR (typeof(NEW.payment_terms_days) = 'integer' AND NEW.payment_terms_days BETWEEN 0 AND 3650))
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM suppliers s WHERE s.normalized_name = NEW.normalized_name AND s.archived_at IS NULL AND s.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:suppliers'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_tags_insert",
        table = "tags",
        sql =
            """
                CREATE TRIGGER guard_tags_insert BEFORE INSERT ON tags
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:tags'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_tags_update",
        table = "tags",
        sql =
            """
                CREATE TRIGGER guard_tags_update BEFORE UPDATE ON tags
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:tags'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_tax_rates_insert",
        table = "tax_rates",
        sql =
            """
                CREATE TRIGGER guard_tax_rates_insert BEFORE INSERT ON tax_rates
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND typeof(NEW.rate_bp) = 'integer' AND NEW.rate_bp BETWEEN 0 AND 10000
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM tax_rates t WHERE t.normalized_name = NEW.normalized_name AND t.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:tax_rates'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_tax_rates_update",
        table = "tax_rates",
        sql =
            """
                CREATE TRIGGER guard_tax_rates_update BEFORE UPDATE ON tax_rates
                WHEN NOT IFNULL((
                  typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
                  AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
                  AND typeof(NEW.rate_bp) = 'integer' AND NEW.rate_bp BETWEEN 0 AND 10000
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM tax_rates t WHERE t.normalized_name = NEW.normalized_name AND t.archived_at IS NULL AND t.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:tax_rates'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_units_insert",
        table = "units",
        sql =
            """
                CREATE TRIGGER guard_units_insert BEFORE INSERT ON units
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND (NEW.symbol IS NULL OR (typeof(NEW.symbol) = 'text' AND length(NEW.symbol) BETWEEN 1 AND 10))
                  AND NEW.kind IN ('COUNT', 'MASS', 'VOLUME', 'LENGTH', 'OTHER')
                  AND NEW.allows_fraction IN (0, 1)
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.system_key IS NOT 'UNIT' OR NEW.archived_at IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM units u WHERE u.normalized_name = NEW.normalized_name AND u.archived_at IS NULL))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:units'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_units_no_delete",
        table = "units",
        sql =
            """
                CREATE TRIGGER guard_units_no_delete BEFORE DELETE ON units
                WHEN OLD.system_key = 'UNIT'
                BEGIN SELECT RAISE(ABORT, 'guard:units_system'); END
            """.trimIndent(),
    ),
    CatalogTrigger(
        name = "guard_units_update",
        table = "units",
        sql =
            """
                CREATE TRIGGER guard_units_update BEFORE UPDATE ON units
                WHEN NOT IFNULL((
                  (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
                  AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
                  AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
                  AND (NEW.symbol IS NULL OR (typeof(NEW.symbol) = 'text' AND length(NEW.symbol) BETWEEN 1 AND 10))
                  AND NEW.kind IN ('COUNT', 'MASS', 'VOLUME', 'LENGTH', 'OTHER')
                  AND NEW.allows_fraction IN (0, 1)
                  AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
                  AND typeof(NEW.created_at) = 'integer'
                  AND typeof(NEW.updated_at) = 'integer'
                  AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
                  AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
                  AND (NEW.system_key IS NOT 'UNIT' OR NEW.archived_at IS NULL)
                  AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM units u WHERE u.normalized_name = NEW.normalized_name AND u.archived_at IS NULL AND u.id <> NEW.id))
                ), 0)
                BEGIN SELECT RAISE(ABORT, 'guard:units'); END
            """.trimIndent(),
    ),
)

/** Una columna. Sin `DEFAULT`, como las crea Room, salvo que diga otro. */
private fun column(
    name: String,
    type: String,
    notNull: Boolean,
    primaryKeyPosition: Int = 0,
    defaultValue: String? = null,
): CatalogColumn = CatalogColumn(name, type, notNull, defaultValue, primaryKeyPosition)

private fun index(name: String, vararg keys: CatalogIndexColumn): CatalogIndex =
    CatalogIndex(name, unique = false, keys.toList())

private fun uniqueIndex(name: String, vararg keys: CatalogIndexColumn): CatalogIndex =
    CatalogIndex(name, unique = true, keys.toList())

private fun key(name: String, descending: Boolean = false): CatalogIndexColumn =
    CatalogIndexColumn(name, descending)
