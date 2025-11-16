package com.wmspro.order.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.LocationAllocationResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@FeignClient(name = "\${wms.services.inventory-service.name}")
interface InventoryServiceClient {

    // ============================================
    // Storage Items Endpoints
    // ============================================

    @GetMapping("/api/v1/storage-items/location-allocation")
    fun getLocationWiseQuantityNumbers(
        @RequestParam(required = false) skuId: Long?,
        @RequestParam(required = false) itemBarcode: String?,
        @RequestParam(required = true) requiredQuantity: Int,
        @RequestParam(required = true) allocationMethod: String
    ): ApiResponse<LocationAllocationResponse>

    /**
     * Get Storage Item IDs by Barcodes
     * Retrieves storage item IDs for given barcodes
     */
    @PostMapping("/api/v1/storage-items/ids-by-barcodes")
    fun getStorageItemIdsByBarcodes(
        @RequestBody request: StorageItemIdsByBarcodesRequest
    ): ApiResponse<List<StorageItemIdResponse>>

    /**
     * API 127: Change Storage Item Location
     * Updates the current location of a storage item with full audit trail
     */
    @PutMapping("/api/v1/storage-items/{itemBarcode}/change-location")
    fun changeStorageItemLocation(
        @PathVariable itemBarcode: String,
        @RequestBody request: ChangeLocationRequest,
        @RequestHeader("Authorization") authToken: String
    ): ApiResponse<ChangeLocationResponse>

    /**
     * Get bulk storage item details by barcodes
     * Returns storage item details including dimensions for multiple barcodes
     */
    @PostMapping("/api/v1/storage-items/bulk-details-by-barcodes")
    fun getBulkStorageItemDetailsByBarcodes(
        @RequestBody request: BulkStorageItemDetailsByBarcodesRequest
    ): ApiResponse<List<StorageItemBarcodeResponse>>

    /**
     * Get storage item details by storage item IDs
     * Returns storage item details including dimensions for multiple storage IDs
     */
    @PostMapping("/api/v1/storage-items/barcodes-by-ids")
    fun getBarcodesByStorageItemIds(
        @RequestBody request: StorageItemBarcodesByIdsRequest
    ): ApiResponse<List<StorageItemBarcodeResponse>>

    // ============================================
    // Quantity Inventory Endpoints
    // ============================================

    /**
     * Reduce quantity for shipment (Scenario 2 - Container-Based)
     * POST /api/v1/quantity-inventory/reduce-quantity
     */
    @PostMapping("/api/v1/quantity-inventory/reduce-quantity")
    fun reduceQuantityForShipment(
        @RequestBody request: ReduceQuantityRequest
    ): ApiResponse<QuantityInventoryResponse>

    /**
     * Reduce quantity with location updates (Scenario 3 - Location-Based)
     * POST /api/v1/quantity-inventory/reduce-quantity-with-locations
     */
    @PostMapping("/api/v1/quantity-inventory/reduce-quantity-with-locations")
    fun reduceQuantityWithLocationUpdate(
        @RequestBody request: ReduceQuantityWithLocationsRequest
    ): ApiResponse<ReduceQuantityWithLocationsResponse>

    /**
     * Batch get quantity inventories by IDs
     * POST /api/v1/quantity-inventory/batch-get
     */
    @PostMapping("/api/v1/quantity-inventory/batch-get")
    fun batchGetByIds(
        @RequestBody request: BatchGetQuantityInventoryRequest
    ): ApiResponse<List<QuantityInventoryResponse>>

    /**
     * Get quantity inventory by ID
     * GET /api/v1/quantity-inventory/{quantityInventoryId}
     */
    @GetMapping("/api/v1/quantity-inventory/{quantityInventoryId}")
    fun getQuantityInventoryById(
        @PathVariable quantityInventoryId: String,
        @RequestHeader(value = "Authorization", required = false) authToken: String?
    ): ApiResponse<QuantityInventoryResponse>

    // ============================================
    // Barcode Reservation Endpoints
    // ============================================

    /**
     * Consume a package barcode (mark as CONSUMED)
     * POST /api/v1/barcode-reservations/consume-package
     */
    @PostMapping("/api/v1/barcode-reservations/consume-package")
    fun consumePackageBarcode(
        @RequestBody request: ConsumePackageBarcodeRequest
    ): ApiResponse<BarcodeReservationResponse>

    /**
     * Batch consume multiple package barcodes
     * POST /api/v1/barcode-reservations/batch-consume-packages
     */
    @PostMapping("/api/v1/barcode-reservations/batch-consume-packages")
    fun batchConsumePackageBarcodes(
        @RequestBody request: BatchConsumePackageBarcodesRequest
    ): ApiResponse<List<BarcodeReservationResponse>>

    /**
     * Get barcode reservation by barcode string
     * GET /api/v1/barcode-reservations/by-barcode/{barcode}
     */
    @GetMapping("/api/v1/barcode-reservations/by-barcode/{barcode}")
    fun getBarcodeReservationByBarcode(
        @PathVariable barcode: String
    ): ApiResponse<BarcodeReservationResponse>

    // ============================================
    // Quantity Transaction Endpoints
    // ============================================

    /**
     * Create shipment transaction (Scenario 2 - Container-Based, no location changes)
     * POST /api/v1/quantity-transactions/create-shipment
     */
    @PostMapping("/api/v1/quantity-transactions/create-shipment")
    fun createShipmentTransaction(
        @RequestBody request: CreateShipmentTransactionRequest
    ): ApiResponse<QuantityTransactionResponse>

    /**
     * Create shipment transaction with location changes (Scenario 3 - Location-Based)
     * POST /api/v1/quantity-transactions/create-shipment-with-locations
     */
    @PostMapping("/api/v1/quantity-transactions/create-shipment-with-locations")
    fun createShipmentTransactionWithLocations(
        @RequestBody request: CreateShipmentTransactionWithLocationsRequest
    ): ApiResponse<QuantityTransactionResponse>

    /**
     * Get all transactions for a specific quantity inventory record
     * GET /api/v1/quantity-transactions/by-quantity-inventory/{quantityInventoryId}
     */
    @GetMapping("/api/v1/quantity-transactions/by-quantity-inventory/{quantityInventoryId}")
    fun getTransactionsByQuantityInventoryId(
        @PathVariable quantityInventoryId: String
    ): ApiResponse<List<QuantityTransactionResponse>>

    /**
     * Get all transactions triggered by a specific fulfillment request
     * GET /api/v1/quantity-transactions/by-fulfillment/{fulfillmentId}
     */
    @GetMapping("/api/v1/quantity-transactions/by-fulfillment/{fulfillmentId}")
    fun getTransactionsByFulfillmentId(
        @PathVariable fulfillmentId: String
    ): ApiResponse<List<QuantityTransactionResponse>>
}

data class StorageItemIdsByBarcodesRequest(
    val barcodes: List<String>
)

data class StorageItemIdResponse(
    val itemBarcode: String,
    val storageItemId: Long
)

/**
 * Request DTO for API 127: Change Storage Item Location
 */
data class ChangeLocationRequest(
    val newLocation: String,
    val taskCode: String,
    val action: String? = "MOVED",
    val notes: String? = null,
    val reason: String? = null
)

/**
 * Response DTO for API 127: Change Storage Item Location
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChangeLocationResponse(
    val storageItemId: Long,
    val itemCode: String,
    val itemType: String,
    val previousLocation: LocationChangeInfo,
    val newLocation: LocationChangeInfo,
    val locationUpdatedAt: String,
    val transactionCreated: TransactionInfo,
    val skuId: Long? = null,
    val message: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocationChangeInfo(
    val locationCode: String,
    val locationType: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionInfo(
    val transactionId: String,
    val transactionType: String,
    val recordedBy: String,
    val triggerTaskId: String
)

/**
 * Request DTO for bulk storage item details by barcodes
 */
data class BulkStorageItemDetailsByBarcodesRequest(
    val barcodes: List<String>
)

/**
 * Request DTO for storage item details by IDs
 */
data class StorageItemBarcodesByIdsRequest(
    val storageItemIds: List<Long>
)

/**
 * Storage Item Details Response (with dimensions)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StorageItemBarcodeResponse(
    val storageItemId: Long,
    val itemBarcode: String,
    val itemType: String,
    val skuId: Long? = null,
    val lengthCm: Double? = null,
    val widthCm: Double? = null,
    val heightCm: Double? = null,
    val volumeCbm: Double? = null,
    val weightKg: Double? = null
)

// ============================================
// Quantity Inventory DTOs
// ============================================

data class ReduceQuantityRequest(
    val quantityInventoryId: String,
    val quantityToShip: Int,
    val locationCode: String,
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

// ============================================
// Barcode Reservation DTOs
// ============================================

data class ConsumePackageBarcodeRequest(
    val packageBarcode: String
)

data class BatchConsumePackageBarcodesRequest(
    val packageBarcodes: List<String>
)

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

// ============================================
// Quantity Transaction DTOs
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