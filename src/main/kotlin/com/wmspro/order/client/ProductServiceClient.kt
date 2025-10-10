package com.wmspro.order.client

import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.BatchSkuRequest
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

@FeignClient(name = "\${wms.services.product-service.name}", path = "/api/v1/products/skus")
interface ProductServiceClient {

    @PostMapping("/batch-details")
    fun getBatchSkuDetails(@RequestBody request: BatchSkuRequest): ApiResponse<Map<String, Any>>
}
