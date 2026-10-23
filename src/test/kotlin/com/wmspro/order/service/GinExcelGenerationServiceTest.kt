package com.wmspro.order.service

import com.wmspro.order.dto.GinDataDto
import com.wmspro.order.dto.GinItemDto
import com.wmspro.order.dto.ShippingAddressDto
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

/**
 * Covers the GIN Excel download. Assertions read the produced bytes back with POI.
 */
class GinExcelGenerationServiceTest {

    private val service = GinExcelGenerationService()

    private fun ginData(
        items: List<GinItemDto> = listOf(
            GinItemDto(
                itemCode = "SKU-1201",
                description = "Blue Widget",
                itemType = "SKU_ITEM",
                quantityOrdered = 40,
                quantityPicked = 40,
                quantityShipped = 38,
                length = 15.5, width = 7.5, height = 0.8,
                cbm = 0.000093, weight = 171.0, unit = "cm"
            ),
            GinItemDto(
                itemCode = "SKU-1202",
                description = "Red Widget",
                itemType = "SKU_ITEM",
                quantityOrdered = 12,
                quantityPicked = 12,
                quantityShipped = 12,
                length = 10.0, width = 5.0, height = 2.0,
                cbm = 0.0001, weight = 90.0, unit = "cm"
            )
        )
    ) = GinDataDto(
        owner = "Digital Stout",
        company = "Infinity Logistics",
        ginNumber = "GIN-2026-00042",
        fulfillmentRequestId = "OFR-001",
        externalOrderNumber = "SO-9911",
        dateIssued = LocalDateTime.of(2026, 8, 14, 11, 0),
        transactionDate = LocalDateTime.of(2026, 8, 14, 11, 30),
        status = "SHIPPED",
        carrier = "FIRST_FLIGHT_COURIER",
        awbNumber = "AWB-123456789",
        trackingUrl = "https://track.example.com/AWB-123456789",
        shippingAddress = ShippingAddressDto(
            name = "Receiving Bay",
            company = "Digital Stout FZC",
            addressLine1 = "Warehouse 4",
            city = "Sharjah",
            country = "AE"
        ),
        customerName = "Ops Team",
        customerEmail = "ops@digitalstout.ae",
        items = items,
        // Derived exactly as GinDataAggregationService derives them, rather than typed in. The
        // hand-written figures that used to sit here were the naive column sums, which is a total
        // the aggregator would never produce.
        totalOrdered = items.sumOf { it.quantityOrdered },
        totalPicked = items.sumOf { it.quantityPicked },
        totalShipped = items.sumOf { it.quantityShipped },
        totalCBM = items.sumOf { (it.cbm ?: 0.0) * it.quantityShipped },
        totalWeight = items.sumOf { (it.weight ?: 0.0) * it.quantityShipped },
        generatedAt = LocalDateTime.of(2026, 8, 20, 10, 0),
        generatedBy = "ops@infinitylogisticsme.com"
    )

    private fun flatten(bytes: ByteArray): List<String> {
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { wb ->
            val sheet = wb.getSheetAt(0)
            return (0..sheet.lastRowNum).flatMap { r ->
                val row = sheet.getRow(r) ?: return@flatMap emptyList()
                (0 until row.lastCellNum.coerceAtLeast(0)).map { c ->
                    row.getCell(c)?.let {
                        when (it.cellType) {
                            CellType.NUMERIC -> it.numericCellValue.toString()
                            CellType.STRING -> it.stringCellValue
                            else -> ""
                        }
                    } ?: ""
                }
            }
        }
    }

    @Test
    @DisplayName("produces a workbook named after the GIN")
    fun producesNamedWorkbook() {
        WorkbookFactory.create(ByteArrayInputStream(service.generateGinExcel(ginData()))).use { wb ->
            assertEquals("GIN-2026-00042", wb.getSheetName(0))
        }
    }

    @Test
    @DisplayName("identifies both parties and the order it belongs to")
    fun identifiesPartiesAndOrder() {
        val flat = flatten(service.generateGinExcel(ginData()))

        assertTrue(flat.contains("GOODS ISSUE NOTE"))
        assertTrue(flat.contains("Infinity Logistics"))
        assertTrue(flat.contains("Digital Stout"))
        assertTrue(flat.contains("GIN-2026-00042"))
        assertTrue(flat.contains("OFR-001"))
        assertTrue(flat.contains("SO-9911"))
    }

    @Test
    @DisplayName("flattens the delivery address onto one line")
    fun flattensAddress() {
        val flat = flatten(service.generateGinExcel(ginData()))

        assertTrue(
            flat.any { it.contains("Warehouse 4") && it.contains("Sharjah") && it.contains("AE") },
            "the address must be present as a single readable line"
        )
    }

    /**
     * The fulfilment service invents an AWB from random digits, hardcodes the carrier regardless of
     * what the order specified, and builds a tracking URL on a reserved example domain that goes
     * nowhere. Printing that is bad; putting it in a spreadsheet a customer may paste into a
     * carrier's tracking page is worse.
     */
    @Test
    @DisplayName("omits the invented AWB, carrier and tracking link")
    fun omitsFabricatedShippingFields() {
        val flat = flatten(service.generateGinExcel(ginData()))

        assertFalse(flat.any { it.contains("AWB-123456789") }, "the AWB is fabricated")
        assertFalse(flat.any { it.contains("track.example.com") }, "the tracking URL goes nowhere")
        assertFalse(flat.any { it.contains("FIRST_FLIGHT_COURIER") }, "the carrier is hardcoded, not chosen")
    }

    @Test
    @DisplayName("ordered, picked and shipped are all numeric so a discrepancy can be spotted")
    fun quantitiesAreNumeric() {
        WorkbookFactory.create(ByteArrayInputStream(service.generateGinExcel(ginData()))).use { wb ->
            val sheet = wb.getSheetAt(0)
            val row = (0..sheet.lastRowNum)
                .mapNotNull { sheet.getRow(it) }
                .first { it.getCell(0)?.toString() == "SKU-1201" }

            assertEquals(40.0, row.getCell(3).numericCellValue, "ordered")
            assertEquals(40.0, row.getCell(4).numericCellValue, "picked")
            assertEquals(38.0, row.getCell(5).numericCellValue, "shipped")
            assertEquals(CellType.NUMERIC, row.getCell(10).cellType, "CBM")
        }
    }

    @Test
    @DisplayName("totals match the aggregated figures")
    fun totalsAreCorrect() {
        WorkbookFactory.create(ByteArrayInputStream(service.generateGinExcel(ginData()))).use { wb ->
            val sheet = wb.getSheetAt(0)
            val total = (0..sheet.lastRowNum)
                .mapNotNull { sheet.getRow(it) }
                .first { it.getCell(0)?.toString() == "TOTAL" }

            // 40+12 ordered and picked, 38+12 shipped; weight 171x38 + 90x12.
            assertEquals(52.0, total.getCell(3).numericCellValue)
            assertEquals(52.0, total.getCell(4).numericCellValue)
            assertEquals(50.0, total.getCell(5).numericCellValue)
            assertEquals(7578.0, total.getCell(11).numericCellValue, 1e-9)
        }
    }

    /**
     * The reason anyone asks for a spreadsheet instead of a PDF is to select a column and read the
     * sum. If that sum disagrees with the TOTAL row directly beneath it, the document contradicts
     * itself — which a PDF could never do.
     *
     * This caught a real defect: the cells carried per-unit CBM and weight while the totals were
     * weighted by quantity shipped.
     */
    @Test
    @DisplayName("summing the CBM and Weight columns reproduces the TOTAL row")
    fun columnsSumToTheirTotals() {
        WorkbookFactory.create(ByteArrayInputStream(service.generateGinExcel(ginData()))).use { wb ->
            val sheet = wb.getSheetAt(0)
            val rows = (0..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
            val total = rows.first { it.getCell(0)?.toString() == "TOTAL" }
            val itemRows = rows.filter { it.getCell(0)?.toString()?.startsWith("SKU-12") == true }
            assertEquals(2, itemRows.size, "both item rows must be found")

            listOf(3 to "Ordered", 4 to "Picked", 5 to "Shipped", 10 to "Total CBM", 11 to "Total Weight")
                .forEach { (col, name) ->
                    val columnSum = itemRows.sumOf { it.getCell(col).numericCellValue }
                    assertEquals(
                        total.getCell(col).numericCellValue, columnSum, 1e-9,
                        "the $name column must sum to its own total"
                    )
                }
        }
    }

    @Test
    @DisplayName("names the truck and driver that took the goods, each value beside its label")
    fun showsTruckAndDriver() {
        val data = ginData().copy(truckNumber = "DXB A 12345", driverName = "Rahul Kumar", driverPhone = "+971 501234567")
        val flat = flatten(service.generateGinExcel(data))

        listOf("Truck No" to "DXB A 12345", "Driver" to "Rahul Kumar", "Driver Phone" to "+971 501234567")
            .forEach { (label, value) ->
                val at = flat.indexOf(label)
                assertTrue(at >= 0, "the $label label must be present")
                assertEquals(value, flat[at + 1], "the $label value must follow its label")
            }
    }

    @Test
    @DisplayName("an order with no truck or driver recorded shows no empty truck or driver rows")
    fun omitsTruckAndDriverWhenAbsent() {
        val flat = flatten(service.generateGinExcel(ginData()))

        listOf("Truck No", "Driver", "Driver Phone").forEach { label ->
            assertFalse(flat.contains(label), "$label must be left out when nothing was recorded")
        }
    }

    @Test
    @DisplayName("a shipment with no items still produces a valid file")
    fun emptyShipmentIsSafe() {
        val flat = flatten(service.generateGinExcel(ginData(items = emptyList())))

        assertTrue(flat.contains("GOODS ISSUE NOTE"))
        assertTrue(flat.any { it.contains("No items recorded") })
    }
}
