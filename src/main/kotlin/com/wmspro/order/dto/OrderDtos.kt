package com.wmspro.order.dto

import com.wmspro.order.enums.*
import com.wmspro.order.model.*
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

// API 133: Create Order Fulfillment Request
data class CreateOfrRequest(
    @field:NotNull(message = "Account ID is required")
    val accountId: Long,

    @field:NotNull(message = "Fulfillment source is required")
    val fulfillmentSource: FulfillmentSource,

    val externalOrderId: String? = null,
    val externalOrderNumber: String? = null,

    @field:NotNull(message = "Customer info is required")
    val customerInfo: CustomerInfoDto,

    @field:NotNull(message = "Shipping address is required")
    val shippingAddress: ShippingAddressDto,

    @field:NotEmpty(message = "Line items cannot be empty")
    val lineItems: List<LineItemDto>,

    val shippingDetails: ShippingDetailsDto? = null,

    val priority: Priority? = Priority.STANDARD,
    val orderValue: OrderValueDto? = null,

    val notes: String? = null,
    val tags: List<String>? = null,
    val customFields: Map<String, Any>? = null,

    @field:NotNull(message = "Execution approach is required")
    val executionApproach: ExecutionApproach
)

data class CustomerInfoDto(
    @field:NotBlank(message = "Customer name is required")
    val name: String,

    @field:NotBlank(message = "Customer email is required")
    val email: String,

    val phone: String? = null
)

data class ShippingAddressDto(
    val name: String? = null,
    val company: String? = null,

    @field:NotBlank(message = "Address line 1 is required")
    val addressLine1: String,

    val addressLine2: String? = null,

    @field:NotBlank(message = "City is required")
    val city: String,

    val state: String? = null,

    @field:NotBlank(message = "Country is required")
    val country: String,

    val postalCode: String? = null,
    val phone: String? = null
)

data class LineItemDto(
    @field:NotNull(message = "Item type is required")
    val itemType: ItemType,

    val skuId: Long? = null,  // Required if itemType is SKU_ITEM
    val itemBarcode: String? = null,  // Required if itemType is BOX or PALLET

    @field:NotNull(message = "Quantity ordered is required")
    @field:Min(1, message = "Quantity ordered must be at least 1")
    val quantityOrdered: Int
)

data class ShippingDetailsDto(
    val carrier: String? = null,
    val requestedServiceType: ServiceType? = null
)

data class OrderValueDto(
    val subtotal: Double? = null,
    val shipping: Double? = null,
    val tax: Double? = null,
    val total: Double? = null,
    val currency: String = "AED"
)

// Response DTOs
data class OfrResponse(
    val fulfillmentId: String,
    val accountId: Long,
    val fulfillmentSource: FulfillmentSource,
    val externalOrderId: String?,
    val externalOrderNumber: String?,
    val fulfillmentStatus: FulfillmentStatus,
    val priority: Priority,
    val executionApproach: ExecutionApproach,
    val qcDetails: QcDetails?,
    val pickingTaskId: String?,
    val packMoveTaskId: String?,
    val pickPackMoveTaskId: String?,
    val loadingTaskId: String?,
    val customerInfo: CustomerInfo,
    val shippingAddress: ShippingAddress,
    val lineItems: List<LineItem>,
    val packages: List<Package>,
    val shippingDetails: ShippingDetails?,
    val statusHistory: List<StatusHistory>,
    val fulfillmentRequestDate: LocalDateTime,
    val orderValue: OrderValue?,
    val ginNumber: String?,
    val ginNotification: GinNotification?,
    val notes: String?,
    val tags: List<String>,
    val customFields: Map<String, Any>,
    val cancelledAt: LocalDateTime?,
    val cancellationReason: String?,
    val createdBy: String?,
    val updatedBy: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(ofr: OrderFulfillmentRequest): OfrResponse {
            return OfrResponse(
                fulfillmentId = ofr.fulfillmentId,
                accountId = ofr.accountId,
                fulfillmentSource = ofr.fulfillmentSource,
                externalOrderId = ofr.externalOrderId,
                externalOrderNumber = ofr.externalOrderNumber,
                fulfillmentStatus = ofr.fulfillmentStatus,
                priority = ofr.priority,
                executionApproach = ofr.executionApproach,
                qcDetails = ofr.qcDetails,
                pickingTaskId = ofr.pickingTaskId,
                packMoveTaskId = ofr.packMoveTaskId,
                pickPackMoveTaskId = ofr.pickPackMoveTaskId,
                loadingTaskId = ofr.loadingTaskId,
                customerInfo = ofr.customerInfo,
                shippingAddress = ofr.shippingAddress,
                lineItems = ofr.lineItems,
                packages = ofr.packages,
                shippingDetails = ofr.shippingDetails,
                statusHistory = ofr.statusHistory,
                fulfillmentRequestDate = ofr.fulfillmentRequestDate,
                orderValue = ofr.orderValue,
                ginNumber = ofr.ginNumber,
                ginNotification = ofr.ginNotification,
                notes = ofr.notes,
                tags = ofr.tags,
                customFields = ofr.customFields,
                cancelledAt = ofr.cancelledAt,
                cancellationReason = ofr.cancellationReason,
                createdBy = ofr.createdBy,
                updatedBy = ofr.updatedBy,
                createdAt = ofr.createdAt,
                updatedAt = ofr.updatedAt
            )
        }
    }
}

// Pagination response
data class PageResponse<T>(
    val data: List<T>,
    val page: Int,
    val limit: Int,
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

/**
 * API 141: Change OFR Status to "PICKUP_DONE" - Request
 */
data class ChangeOfrStatusToPickupDoneRequest(
    val taskCode: String? = null,
    val itemsToPick: List<ItemsToPickDto>
)

data class ItemsToPickDto(
    val storageItemId: Long?,
    val itemBarcode: String?,
    val skuId: Long?,
    val itemType: String,
    val totalQuantityRequired: Int?,
    val quantityPicked: Int?,
    val picked: Boolean,
    val pickedAt: LocalDateTime?
)

/**
 * API 141: Change OFR Status to "PICKUP_DONE" - Response
 */
data class ChangeOfrStatusToPickupDoneResponse(
    val fulfillmentRequestId: String,
    val fulfillmentStatus: String,
    val updatedAt: LocalDateTime,
    val nextTask: NextTaskDto?
)

data class NextTaskDto(
    val taskCode: String,
    val taskType: String
)

/**
 * Web List View DTOs for Order Fulfillment Requests
 */

/**
 * OFR List Item Response for Web List Views
 * Used in stage-based list views (Picking Pending, Pack & Move Pending, etc.)
 */
data class OfrListItemResponse(
    val fulfillmentId: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,

    // Customer details
    val customerName: String,
    val accountName: String?,

    // Stage-specific fields
    val pickingId: String? = null,          // For Picking Pending stage
    val packMoveId: String? = null,         // For Pack & Move Pending stage
    val pickPackMoveId: String? = null,     // For Pick Pack Move Pending stage
    val loadingId: String? = null,          // For Loading Done & GIN Sent stages
    val awb: String? = null,                // For Ready To Dispatch, Loading Done stages
    val gin: String? = null,                // For Ready To Dispatch, GIN Sent stages

    // Common metrics
    val items: Int,                         // Sum of quantityOrdered from all lineItems
    val locations: Int? = null,             // Count of distinct locations (for picking stages)
    val packages: Int? = null               // Count of packages (for Ready To Dispatch stage)
)

/**
 * Stage Summary Response
 * Returns count of OFRs in each stage
 */
data class OfrStageSummaryResponse(
    val pickingPending: Long,
    val packMovePending: Long,
    val pickPackMovePending: Long,
    val readyToDispatch: Long,
    val loadingDoneGinPending: Long,
    val ginSent: Long
)

/**
 * API 154: Create AWB For All Packages - Response
 * AWB Configuration Object (Hardcoded until Shipping Service integration)
 */
data class AwbConfigurationResponse(
    val shipmentId: String,
    val awbNumber: String,
    val carrier: String,
    val createdViaApi: Boolean,
    val requestedServiceType: String,
    val selectedServiceCode: String,
    val trackingUrl: String,
    val shippingLabelPdf: String,
    val awbPdf: String
)
