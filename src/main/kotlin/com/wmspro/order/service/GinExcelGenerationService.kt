package com.wmspro.order.service

import com.wmspro.common.utils.DocumentExcelWriter
import com.wmspro.common.utils.DocumentExcelWriter.Column
import com.wmspro.common.utils.DocumentExcelWriter.ColumnType
import com.wmspro.common.utils.DocumentExcelWriter.HeaderField
import com.wmspro.common.utils.DocumentExcelWriter.Value
import com.wmspro.order.dto.GinDataDto
import com.wmspro.order.dto.ShippingAddressDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Renders a GIN as an XLSX, alongside [GinPdfGenerationService] which renders the same document
 * as a PDF.
 *
 * Both consume the identical [GinDataDto], so the two formats cannot report different figures for
 * the same shipment.
 *
 * ## AWB and tracking are deliberately excluded
 *
 * `GinDataDto` carries `awbNumber`, `carrier` and `trackingUrl`, and the PDF may show them, but
 * they are not real: the fulfillment service invents an AWB from random digits, hardcodes the
 * carrier regardless of what the order specified, and builds a tracking URL on a reserved example
 * domain that goes nowhere. Printing them is bad enough; putting them in a spreadsheet a customer
 * may act on — or paste into a carrier's tracking page — is worse.
 */
@Service
class GinExcelGenerationService {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun generateGinExcel(ginData: GinDataDto): ByteArray {
        logger.info("Generating GIN Excel for GIN: {}", ginData.ginNumber)

        val bytes = DocumentExcelWriter.write(
            documentTitle = "GOODS ISSUE NOTE",
            sheetName = ginData.ginNumber,
            headerFields = headerFields(ginData),
            columns = COLUMNS,
            rows = ginData.items.map { item ->
                listOf(
                    Value.Text(item.itemCode),
                    Value.Text(item.description),
                    Value.Text(item.itemType),
                    Value.Integer(item.quantityOrdered),
                    Value.Integer(item.quantityPicked),
                    Value.Integer(item.quantityShipped),
                    Value.Decimal(item.length),
                    Value.Decimal(item.width),
                    Value.Decimal(item.height),
                    Value.Text(item.unit),
                    // Line totals, not per-unit figures — see COLUMNS. Weighted by quantity
                    // *shipped*, which is what the document's totals are weighted by.
                    Value.Decimal(item.cbm?.let { it * item.quantityShipped }),
                    Value.Decimal(item.weight?.let { it * item.quantityShipped })
                )
            },
            totals = mapOf(
                3 to Value.Integer(ginData.totalOrdered),
                4 to Value.Integer(ginData.totalPicked),
                5 to Value.Integer(ginData.totalShipped),
                10 to Value.Decimal(ginData.totalCBM),
                11 to Value.Decimal(ginData.totalWeight)
            ),
            footNote = buildString {
                append("Generated ${DocumentExcelWriter.formatDateTime(ginData.generatedAt)}")
                ginData.generatedBy?.let { append(" by $it") }
            }
        )

        logger.info("GIN Excel generated successfully. Size: {} bytes", bytes.size)
        return bytes
    }

    private fun headerFields(data: GinDataDto): List<HeaderField> = listOf(
        HeaderField("Warehouse", data.company),
        HeaderField("Customer", data.owner),
        HeaderField("GIN Number", data.ginNumber),
        HeaderField("Order Reference", data.fulfillmentRequestId),
        HeaderField("External Order No", data.externalOrderNumber),
        HeaderField("Client Reference", data.clientReferenceNum),
        HeaderField("Status", data.status),
        HeaderField("Date Issued", DocumentExcelWriter.formatDate(data.dateIssued)),
        HeaderField("Transaction Date", DocumentExcelWriter.formatDate(data.transactionDate)),
        HeaderField("Customer Reference", data.customerRef),
        HeaderField("Warehouse Reference", data.warehouseRef),
        HeaderField("Contact Name", data.customerName),
        HeaderField("Contact Email", data.customerEmail),
        HeaderField("Contact Phone", data.customerPhone),
        HeaderField("Ship To", formatAddress(data.shippingAddress))
    )

    /** Flattened onto one line — a spreadsheet header block has no room for a stacked address. */
    private fun formatAddress(address: ShippingAddressDto?): String? {
        if (address == null) return null
        return listOfNotNull(
            address.name,
            address.company,
            address.addressLine1,
            address.addressLine2,
            address.city,
            address.state,
            address.postalCode,
            address.country
        ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    }

    private companion object {
        /**
         * Length, Width and Height describe one unit. CBM and Weight are the *line* figures —
         * per-unit multiplied by the quantity shipped — because `GinDataAggregationService`
         * computes the document totals the same way. A per-unit value under a quantity-weighted
         * total gives a sheet that disagrees with itself the moment somebody selects the column,
         * which is precisely what a recipient asking for Excel intends to do.
         */
        val COLUMNS = listOf(
            Column("Item Code", 24),
            Column("Description", 34),
            Column("Type", 12),
            Column("Ordered", 11, ColumnType.INTEGER),
            Column("Picked", 11, ColumnType.INTEGER),
            Column("Shipped", 11, ColumnType.INTEGER),
            Column("Length", 10, ColumnType.DECIMAL),
            Column("Width", 10, ColumnType.DECIMAL),
            Column("Height", 10, ColumnType.DECIMAL),
            Column("Unit", 8),
            // Six decimals: a single e-commerce piece is ~0.0001 m3 and would otherwise read 0.000.
            Column("Total CBM", 14, ColumnType.PRECISE_DECIMAL),
            Column("Total Weight", 14, ColumnType.DECIMAL)
        )
    }
}
