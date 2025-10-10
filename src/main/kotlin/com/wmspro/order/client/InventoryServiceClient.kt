package com.wmspro.order.client

import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.LocationAllocationResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
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
}
