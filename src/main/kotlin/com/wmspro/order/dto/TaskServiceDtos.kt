package com.wmspro.order.dto

import com.wmspro.order.enums.TaskPriority

// Task Service DTOs
data class CreateTaskRequest(
    val taskType: String,
    val warehouseId: String,
    val assignedTo: String? = null,
    val accountIds: List<Long>? = null,
    val priority: TaskPriority? = null,
    val pickingDetails: PickingDetailsDto? = null,
    val pickPackMoveDetails: PickPackMoveDetailsDto? = null
)

data class PickingDetailsDto(
    val fulfillmentRequestId: String,
    val itemsToPick: List<PickingItemDto>
)

data class PickingItemDto(
    val storageItemId: Long? = null,
    val itemBarcode: String? = null,
    val skuId: Long? = null,
    val itemType: String,
    val totalQuantityRequired: Int? = null,
    val pickMethod: String = "RANDOM",
    val pickupLocations: List<PickupLocationDto>
)

data class PickupLocationDto(
    val locationCode: String,
    val itemRange: String
)

data class PickPackMoveDetailsDto(
    val fulfillmentRequestId: String,
    val itemsToPick: List<PickingItemDto>
)

data class TaskResponse(
    val taskId: String,
    val taskCode: String,
    val taskType: String,
    val warehouseId: String
)
