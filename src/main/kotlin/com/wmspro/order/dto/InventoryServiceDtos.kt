package com.wmspro.order.dto

// Inventory Service DTOs
data class LocationAllocationRequest(
    val skuId: Long? = null,
    val itemBarcode: String? = null,
    val requiredQuantity: Int,
    val allocationMethod: String
)

data class LocationQuantityInfo(
    val locationCode: String,
    val itemBarcodes: List<String>
)

data class LocationAllocationResponse(
    val locations: List<LocationQuantityInfo>,
    val totalItemsFound: Int,
    val allocationMethod: String
)
