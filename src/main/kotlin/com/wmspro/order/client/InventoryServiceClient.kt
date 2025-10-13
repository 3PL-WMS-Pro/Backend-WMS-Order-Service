package com.wmspro.order.client

import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.LocationAllocationResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

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
}

data class StorageItemIdsByBarcodesRequest(
    val barcodes: List<String>
)

data class StorageItemIdResponse(
    val itemBarcode: String,
    val storageItemId: Long
)