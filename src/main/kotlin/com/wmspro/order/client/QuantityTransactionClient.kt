package com.wmspro.order.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.wmspro.common.dto.ApiResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.*

/**
 * Feign client for Quantity Transaction operations
 * Calls Inventory Service endpoints for creating quantity transaction records during outbound fulfillment
 */
@FeignClient(name = "\${wms.services.inventory-service.name}", path = "/api/v1/quantity-transactions")
interface QuantityTransactionClient {

    /**
     * Create shipment transaction (Scenario 2 - Container-Based, no location changes)
     * POST /api/v1/quantity-transactions/create-shipment
     */
    @PostMapping("/create-shipment")
    fun createShipmentTransaction(
        @RequestBody request: CreateShipmentTransactionRequest
    ): ApiResponse<QuantityTransactionResponse>

    /**
     * Create shipment transaction with location changes (Scenario 3 - Location-Based)
     * POST /api/v1/quantity-transactions/create-shipment-with-locations
     */
    @PostMapping("/create-shipment-with-locations")
    fun createShipmentTransactionWithLocations(
        @RequestBody request: CreateShipmentTransactionWithLocationsRequest
    ): ApiResponse<QuantityTransactionResponse>

    /**
     * Get all transactions for a specific quantity inventory record
     * GET /api/v1/quantity-transactions/by-quantity-inventory/{quantityInventoryId}
     */
    @GetMapping("/by-quantity-inventory/{quantityInventoryId}")
    fun getTransactionsByQuantityInventoryId(
        @PathVariable quantityInventoryId: String
    ): ApiResponse<List<QuantityTransactionResponse>>

    /**
     * Get all transactions triggered by a specific fulfillment request
     * GET /api/v1/quantity-transactions/by-fulfillment/{fulfillmentId}
     */
    @GetMapping("/by-fulfillment/{fulfillmentId}")
    fun getTransactionsByFulfillmentId(
        @PathVariable fulfillmentId: String
    ): ApiResponse<List<QuantityTransactionResponse>>
}

// ============================================
// Request DTOs
// ============================================

data class CreateShipmentTransactionRequest(
    val quantityInventoryId: String,
    val beforeQuantity: Int,
    val afterQuantity: Int,
    val fulfillmentId: String,
    val user: String
)

data class CreateShipmentTransactionWithLocationsRequest(
    val quantityInventoryId: String,
    val beforeQuantity: Int,
    val afterQuantity: Int,
    val beforeLocations: List<LocationAllocationDto>,
    val afterLocations: List<LocationAllocationDto>,
    val fulfillmentId: String,
    val user: String
)

data class LocationAllocationDto(
    val locationCode: String,
    val quantity: Int,
    val allocatedAt: String
)

// ============================================
// Response DTOs
// ============================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuantityTransactionResponse(
    val transactionId: String,
    val quantityInventoryId: String,
    val accountId: Long,
    val transactionType: String,
    val beforeQuantity: Int,
    val afterQuantity: Int,
    val quantityChange: Int,
    val beforeLocations: List<LocationAllocationDto>? = null,
    val afterLocations: List<LocationAllocationDto>? = null,
    val triggerFulfillmentId: String? = null,
    val triggerTaskId: String? = null,
    val triggerReceivingRecordId: String? = null,
    val user: String,
    val notes: String? = null,
    val reason: String? = null,
    val timestamp: String
)
