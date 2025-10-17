package com.wmspro.order.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.LocationAllocationResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@FeignClient(name = "\${wms.services.inventory-service.name}", path = "/api/v1/storage-items")
interface InventoryServiceClient {

    @GetMapping("/location-allocation")
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
    @PostMapping("/ids-by-barcodes")
    fun getStorageItemIdsByBarcodes(
        @RequestBody request: StorageItemIdsByBarcodesRequest
    ): ApiResponse<List<StorageItemIdResponse>>

    /**
     * API 127: Change Storage Item Location
     * Updates the current location of a storage item with full audit trail
     */
    @PutMapping("/{itemBarcode}/change-location")
    fun changeStorageItemLocation(
        @PathVariable itemBarcode: String,
        @RequestBody request: ChangeLocationRequest,
        @RequestHeader("Authorization") authToken: String
    ): ApiResponse<ChangeLocationResponse>
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