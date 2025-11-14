package com.wmspro.order.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.wmspro.common.dto.ApiResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.*

/**
 * Feign client for Barcode Reservation operations
 * Calls Inventory Service endpoints for package barcode consumption during outbound fulfillment
 */
@FeignClient(name = "\${wms.services.inventory-service.name}", path = "/api/v1/barcode-reservations")
interface BarcodeReservationClient {

    /**
     * Consume a package barcode (mark as CONSUMED)
     * POST /api/v1/barcode-reservations/consume-package
     */
    @PostMapping("/consume-package")
    fun consumePackageBarcode(
        @RequestBody request: ConsumePackageBarcodeRequest
    ): ApiResponse<BarcodeReservationResponse>

    /**
     * Batch consume multiple package barcodes
     * POST /api/v1/barcode-reservations/batch-consume-packages
     */
    @PostMapping("/batch-consume-packages")
    fun batchConsumePackageBarcodes(
        @RequestBody request: BatchConsumePackageBarcodesRequest
    ): ApiResponse<List<BarcodeReservationResponse>>

    /**
     * Get barcode reservation by barcode string
     * GET /api/v1/barcode-reservations/by-barcode/{barcode}
     */
    @GetMapping("/by-barcode/{barcode}")
    fun getBarcodeReservationByBarcode(
        @PathVariable barcode: String
    ): ApiResponse<BarcodeReservationResponse>

    /**
     * Check if a barcode is available (status = RESERVED)
     * This is a convenience method to validate package barcodes before consumption
     */
    fun isBarcodeAvailable(barcode: String): Boolean {
        return try {
            val response = getBarcodeReservationByBarcode(barcode)
            response.data?.status == "RESERVED"
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================
// Request DTOs
// ============================================

data class ConsumePackageBarcodeRequest(
    val packageBarcode: String
)

data class BatchConsumePackageBarcodesRequest(
    val packageBarcodes: List<String>
)

// ============================================
// Response DTOs
// ============================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class BarcodeReservationResponse(
    val id: String,
    val barcode: String,
    val reservedItemId: Long,
    val status: String,
    val accountId: Long,
    val itemType: String? = null,
    val createdAt: String
)
