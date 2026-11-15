package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryAvailability
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import kotlinx.datetime.number

/**
 * Las cuentas del `Resumen de gestión`: con qué cifras sale, qué archivos lleva y cómo se llama.
 *
 * Las cifras salen de [PeriodMetricsCalculator], la misma cuenta de `Números`: el resumen no tiene
 * una fórmula propia que pueda dar otro número para el mismo período.
 */
object ManagementSummaryRules {
    /** Cuántos métodos de pago muestra la pantalla. El PDF los lleva todos. */
    const val SCREEN_METHODS = 10

    private const val YEAR_DIGITS = 4

    /**
     * El resumen de [period] desde sus sumas. [tax] es `null` si el negocio no cobra impuesto.
     * Devuelve [Checked.Overflow] si una cifra no cabe en `Long`: solo con sumas absurdas.
     */
    fun summaryOf(
        period: ReportPeriod,
        totals: PeriodTotals,
        tax: TaxEstimate?,
        methods: List<PaymentMethodTotal>,
        notices: PeriodNoticeCounts,
        availability: SummaryAvailability,
    ): Checked<ManagementSummary> = PeriodMetricsCalculator.of(totals).map { metrics ->
        ManagementSummary(
            period = period,
            profit = metrics.profit,
            tax = tax,
            methods = methods,
            salesWithoutCost = notices.salesWithoutCost,
            // Una compra no cambia la ganancia, pero es un movimiento que el resumen lleva.
            hasMovements = metrics.hasMovements || availability.hasPurchases,
            contents = contentsOf(availability),
        )
    }

    /**
     * Lo que se ofrece incluir: ventas y compras siempre, y el inventario al cierre y los clientes
     * y proveedores solo si hay datos.
     */
    fun contentsOf(availability: SummaryAvailability): List<SummaryContent> = buildList {
        add(SummaryContent.SALES_AND_PAYMENTS)
        add(SummaryContent.PURCHASES_AND_EXPENSES)
        if (availability.hasInventory) add(SummaryContent.CLOSING_INVENTORY)
        if (availability.hasParties) add(SummaryContent.CUSTOMERS_AND_SUPPLIERS)
    }

    /**
     * Lo que va encendido al abrir: ventas y compras. El inventario y los clientes y proveedores
     * se encienden a mano, porque no siempre se piden y los clientes son datos de otras personas.
     */
    fun defaultChoice(offered: List<SummaryContent>): Set<SummaryContent> = offered.filter {
        it == SummaryContent.SALES_AND_PAYMENTS || it == SummaryContent.PURCHASES_AND_EXPENSES
    }.toSet()

    /**
     * Los archivos CSV de [chosen], en el orden de [SummaryContent] y sin repetir. Solo entra lo
     * que [offered] ofrece: un grupo apagado o sin datos no deja ningún archivo.
     */
    fun filesOf(chosen: Set<SummaryContent>, offered: List<SummaryContent>): List<SummaryFile> =
        SummaryContent.entries
            .filter { it in chosen && it in offered }
            .flatMap(::filesOf)

    private fun filesOf(content: SummaryContent): List<SummaryFile> = when (content) {
        SummaryContent.SALES_AND_PAYMENTS -> tables(
            CsvExportTable.SALES,
            CsvExportTable.SALE_LINES,
            CsvExportTable.SALE_PAYMENTS,
            CsvExportTable.SALE_RETURNS,
            CsvExportTable.SALE_RETURN_LINES,
        )

        SummaryContent.PURCHASES_AND_EXPENSES -> tables(
            CsvExportTable.PURCHASES,
            CsvExportTable.PURCHASE_LINES,
            CsvExportTable.PURCHASE_RETURNS,
            CsvExportTable.PURCHASE_RETURN_LINES,
            CsvExportTable.EXPENSES,
        )

        SummaryContent.CLOSING_INVENTORY -> listOf(SummaryFile.ClosingInventory)

        SummaryContent.CUSTOMERS_AND_SUPPLIERS ->
            tables(CsvExportTable.CUSTOMERS, CsvExportTable.SUPPLIERS)
    }

    private fun tables(vararg tables: CsvExportTable): List<SummaryFile> =
        tables.map { SummaryFile.Table(it) }

    /** Si [period] todavía no termina el día [today]: sus cifras pueden cambiar. */
    fun inProgress(period: ReportPeriod, today: BusinessDate): Boolean = today <= period.end

    /**
     * La parte del período en el nombre del archivo: `2026-10` para un mes, la fecha para un día,
     * y `2026-10-01_2026-10-15` para un rango. Solo cifras, guiones y un guion bajo: vale en
     * cualquier idioma y en cualquier sistema de archivos.
     */
    fun fileStamp(period: ReportPeriod): String = when (period) {
        is ReportPeriod.Month ->
            period.yearMonth.year.toString().padStart(YEAR_DIGITS, '0') + "-" +
                period.yearMonth.month.number.toString().padStart(2, '0')

        else ->
            if (period.start == period.end) {
                period.start.toString()
            } else {
                "${period.start}_${period.end}"
            }
    }

    /** Los métodos que caben en pantalla: los [SCREEN_METHODS] primeros, de mayor a menor. */
    fun screenMethods(methods: List<PaymentMethodTotal>): List<PaymentMethodTotal> =
        methods.take(SCREEN_METHODS)
}
