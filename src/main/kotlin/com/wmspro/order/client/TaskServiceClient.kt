package com.wmspro.order.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.CreateTaskRequest
import com.wmspro.order.dto.TaskResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDateTime

@FeignClient(name = "\${wms.services.task-service.name}", path = "/api/v1/tasks")
interface TaskServiceClient {

    @PostMapping
    fun createTask(
        @RequestBody request: CreateTaskRequest,
        @RequestHeader("Authorization") authToken: String
    ): ApiResponse<TaskResponse>

    @GetMapping("/{taskCode}/web-details")
    fun getTaskWebDetails(
        @PathVariable taskCode: String,
        @RequestParam(required = false) accountId: Long?
    ): ApiResponse<WebTaskDetailsResponse>
}

/**
 * Web Task Details Response from Task Service
 * Used for fetching complete task details for OFR detail pages
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WebTaskDetailsResponse(
    val taskCode: String,
    val taskType: String,
    val assignedTo: String?,
    val createdAt: LocalDateTime,
    val actualStartTime: LocalDateTime?,
    val actualEndTime: LocalDateTime?,
    val timeSpentMinutes: Int?,
    val slaDeadline: LocalDateTime?,

    // Outbound task details
    val pickingDetails: WebPickingDetails?,
    val packMoveDetails: WebPackMoveDetails?,
    val pickPackMoveDetails: WebPickPackMoveDetails?,
    val loadingDetails: WebLoadingDetails?
)

// ========== Picking Task Details ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPickingDetails(
    val fulfillmentRequestId: String,
    val itemsToPick: List<WebPickingItem>,
    val packingZoneVisits: List<WebPackingZoneVisit>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPickingItem(
    val storageItemId: Long?,
    val itemBarcode: String?,
    val skuId: Long?,
    val itemType: String,
    val itemsPicked: List<WebPickedItemDetail>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPickedItemDetail(
    val storageItemId: Long,
    val itemBarcode: String,
    val pickedFromLocation: String,
    val pickedAt: LocalDateTime,
    val pickedBy: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPackingZoneVisit(
    val packingZone: String,
    val scannedAt: LocalDateTime,
    val itemsDropped: List<WebDroppedItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebDroppedItem(
    val storageItemId: Long?,
    val storageItemBarcode: String?,
    val itemType: String,
    val skuId: Long?
)

// ========== Pack Move Task Details ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPackMoveDetails(
    val fulfillmentRequestId: String,
    val itemsToVerify: List<WebPackMoveItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPackMoveItem(
    val storageItemId: Long,
    val itemBarcode: String,
    val skuId: Long?,
    val itemType: String
)

// ========== Pick Pack Move Task Details ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPickPackMoveDetails(
    val fulfillmentRequestId: String,
    val itemsToPick: List<WebPickPackMoveItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPickPackMoveItem(
    val storageItemId: Long?,
    val skuId: Long?,
    val itemBarcode: String?,
    val itemType: String,
    val pickupLocations: List<WebPickupLocation>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPickupLocation(
    val locationCode: String
)

// ========== Loading Task Details ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebLoadingDetails(
    val fulfillmentRequestIds: List<String>,
    val truckInfo: WebLoadingTruckInfo?,
    val companyCards: List<WebLoadingCompanyCard>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebLoadingTruckInfo(
    val truckNumber: String,
    val driverPhoto: String?,
    val driverIdentityProof: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WebLoadingCompanyCard(
    val accountId: Long,
    val ginNumber: String,
    val signedGinDocument: String?,
    val packagePhotos: List<String>?
)
