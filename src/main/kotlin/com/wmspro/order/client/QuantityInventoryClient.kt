package com.wmspro.order.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.wmspro.common.dto.ApiResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.*

/**
 * Feign client for Quantity-Based Inventory operations
 * Calls Inventory Service endpoints for quantity reduction during outbound fulfillment
 */
@FeignClient(name = "\${wms.services.inventory-service.name}", path = "/api/v1/quantity-inventory")
interface QuantityInventoryClient {

    /**
     * Reduce quantity for shipment (Scenario 2 - Container-Based)
     * POST /api/v1/quantity-inventory/reduce-quantity
     */
    @PostMapping("/reduce-quantity")
    fun reduceQuantityForShipment(
        @RequestBody request: ReduceQuantityRequest
    ): ApiResponse<QuantityInventoryResponse>

    /**
     * Reduce quantity with location updates (Scenario 3 - Location-Based)
     * POST /api/v1/quantity-inventory/reduce-quantity-with-locations
     */
    @PostMapping("/reduce-quantity-with-locations")
    fun reduceQuantityWithLocationUpdate(
        @RequestBody request: ReduceQuantityWithLocationsRequest
    ): ApiResponse<ReduceQuantityWithLocationsResponse>

    /**
     * Batch get quantity inventories by IDs
     * POST /api/v1/quantity-inventory/batch-get
     */
    @PostMapping("/batch-get")
    fun batchGetByIds(
        @RequestBody request: BatchGetQuantityInventoryRequest
    ): ApiResponse<List<QuantityInventoryResponse>>

    /**
     * Get quantity inventory by ID
     * GET /api/v1/quantity-inventory/{quantityInventoryId}
     */
    @GetMapping("/{quantityInventoryId}")
    fun getQuantityInventoryById(
        @PathVariable quantityInventoryId: String,
        @RequestHeader(value = "Authorization", required = false) authToken: String?
    ): ApiResponse<QuantityInventoryResponse>
}

// ============================================
// Request DTOs
// ============================================

data class ReduceQuantityRequest(
    val quantityInventoryId: String,
    val quantityToShip: Int,
    val triggeredBy: String
)

data class ReduceQuantityWithLocationsRequest(
    val quantityInventoryId: String,
    val locationReductions: List<LocationReductionDto>,
    val triggeredBy: String
)

data class LocationReductionDto(
    val locationCode: String,
    val quantityToShip: Int
)

data class BatchGetQuantityInventoryRequest(
    val quantityInventoryIds: List<String>
)

// ============================================
// Response DTOs
// ============================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuantityInventoryResponse(
    val quantityInventoryId: String,
    val accountId: Long,
    val accountName: String? = null,
    val receivingRecordId: String,
    val itemType: String,
    val skuId: Long? = null,
    val skuInfo: SkuInfo? = null,
    val totalQuantity: Int,
    val availableQuantity: Int,
    val reservedQuantity: Int,
    val shippedQuantity: Int,
    val locationAllocations: List<LocationAllocationResponseDto>,
    val parentContainerId: Long? = null,
    val parentContainerBarcode: String? = null,
    val description: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocationAllocationResponseDto(
    val locationCode: String,
    val quantity: Int,
    val allocatedAt: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SkuInfo(
    val skuId: Long,
    val name: String,
    val sku: String,
    val upc: String? = null,
    val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReduceQuantityWithLocationsResponse(
    val quantityInventory: QuantityInventoryResponse,
    val updatedLocations: List<LocationAllocationResponseDto>
)
