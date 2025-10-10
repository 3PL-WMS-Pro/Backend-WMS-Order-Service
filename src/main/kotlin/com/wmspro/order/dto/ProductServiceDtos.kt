package com.wmspro.order.dto

// Product Service DTOs
data class BatchSkuRequest(
    val skuIds: List<Long>,
    val fields: List<String>? = null
)
