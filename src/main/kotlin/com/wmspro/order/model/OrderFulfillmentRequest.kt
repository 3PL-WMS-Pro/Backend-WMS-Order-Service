package com.wmspro.order.model

import com.wmspro.order.enums.*
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "order_fulfillment_requests")
data class OrderFulfillmentRequest(
    @Id
    val fulfillmentId: String,  // Primary key: "OFR-001", "OFR-002", etc.

    @Indexed
    val accountId: Long,  // Warehouse customer whose order is being fulfilled

    val fulfillmentSource: FulfillmentSource,

    @Indexed
    val externalOrderId: String? = null,  // Client's original order ID
    val externalOrderNumber: String? = null,  // Client's order number

    var fulfillmentStatus: FulfillmentStatus = FulfillmentStatus.RECEIVED,
    val priority: Priority = Priority.STANDARD,
    val executionApproach: ExecutionApproach,

    // QC details
    val qcDetails: QcDetails? = null,

    // Task IDs
    var pickingTaskId: String? = null,
    var packMoveTaskId: String? = null,
    var pickPackMoveTaskId: String? = null,
    var loadingTaskId: String? = null,

    // Customer information
    val customerInfo: CustomerInfo,

    // Shipping address
    val shippingAddress: ShippingAddress,

    // Order line items
    val lineItems: MutableList<LineItem> = mutableListOf(),

    // Packages created during PICK_PACK_MOVE task
    val packages: MutableList<Package> = mutableListOf(),

    // Shipping configuration
    val shippingDetails: ShippingDetails,

    // Status tracking
    val statusHistory: MutableList<StatusHistory> = mutableListOf(),

    val fulfillmentRequestDate: LocalDateTime = LocalDateTime.now(),

    // Financial summary
    val orderValue: OrderValue? = null,

    // GIN (Goods Issue Note) management
    val ginNumber: String? = null,
    val ginNotification: GinNotification? = null,

    val notes: String? = null,
    val tags: List<String> = listOf(),
    val customFields: Map<String, Any> = mapOf(),

    val cancelledAt: LocalDateTime? = null,
    val cancellationReason: String? = null,

    val createdBy: String? = null,
    val updatedBy: String? = null,

    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

// Nested data classes

data class CustomerInfo(
    val name: String,
    val email: String,
    val phone: String? = null
)

data class ShippingAddress(
    val name: String? = null,
    val company: String? = null,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val state: String? = null,
    val country: String,
    val postalCode: String? = null,
    val phone: String? = null
)

data class LineItem(
    val lineItemId: String,  // Auto-generated: "LI-001", "LI-002", etc.
    val itemType: ItemType,
    val skuId: Long? = null,  // Required if itemType is SKU_ITEM
    val itemBarcode: String? = null,  // Required if itemType is BOX or PALLET
    var allocationMethod: AllocationMethod? = null,  // Can be set after creation

    val quantityOrdered: Int,
    var quantityPicked: Int = 0,
    var quantityShipped: Int = 0,

    val allocatedItems: MutableList<AllocatedItem> = mutableListOf()
)

data class AllocatedItem(
    val storageItemId: Long,  // Reference to StorageItem
    val location: String,

    // Picking tracking per storage item
    var picked: Boolean = false,
    var pickedAt: LocalDateTime? = null,
    var pickedBy: String? = null
)

data class Package(
    val packageId: String,  // Unique package identifier
    val packageBarcode: String? = null,  // Scanned package barcode

    // Package physical details
    val dimensions: PackageDimensions? = null,
    val weight: PackageWeight? = null,

    // Items assigned to this package
    val assignedItems: MutableList<AssignedItem> = mutableListOf(),

    // Dispatch tracking
    val dispatchArea: String? = null,
    val dispatchAreaBarcode: String? = null,
    var droppedAtDispatch: Boolean = false,
    var dispatchScannedAt: LocalDateTime? = null,

    // Package lifecycle
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val createdByTask: String? = null,
    val savedAt: LocalDateTime? = null,

    // Shipping details (populated during LOADING task)
    var loadedOnTruck: Boolean = false,
    val truckNumber: String? = null,
    val loadedAt: LocalDateTime? = null
)

data class PackageDimensions(
    val length: Double,
    val width: Double,
    val height: Double,
    val unit: String = "cm"
)

data class PackageWeight(
    val value: Double,
    val unit: String = "kg"
)

data class AssignedItem(
    val storageItemId: Long,
    val skuId: Long? = null,
    val itemType: ItemType,
    val itemBarcodes: List<String> = listOf(),
    val quantity: Int? = null  // For SKU items
)

data class ShippingDetails(
    val awbCondition: AwbCondition = AwbCondition.CREATE_FOR_CUSTOMER,

    val carrier: String? = null,
    val requestedServiceType: ServiceType? = null,
    val selectedServiceCode: String? = null,

    val shipmentId: String? = null,
    val awbNumber: String? = null,
    val awbPdf: String? = null,  // Base64 encoded AWB PDF
    val trackingUrl: String? = null,
    val shippingLabelPdf: String? = null
)

data class QcDetails(
    val qcInspectionId: String? = null,
    val qcCompletedAt: LocalDateTime? = null,
    val qcCompletedBy: String? = null
)

data class StatusHistory(
    val status: FulfillmentStatus,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val user: String? = null,
    val automated: Boolean = false,
    val notes: String? = null
)

data class OrderValue(
    val subtotal: Double? = null,
    val shipping: Double? = null,
    val tax: Double? = null,
    val total: Double? = null,
    val currency: String = "AED"
)

data class GinNotification(
    var sentToCustomer: Boolean = false,
    val sentAt: LocalDateTime? = null,
    val customerEmail: String? = null,
    val signedGinDocument: String? = null  // URL/path to signed GIN
)
