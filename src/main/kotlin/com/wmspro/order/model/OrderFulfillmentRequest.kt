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
    val clientReferenceNum: String? = null,  // Client's reference number (optional)

    var fulfillmentStatus: FulfillmentStatus = FulfillmentStatus.RECEIVED,
    val priority: Priority = Priority.STANDARD,
    val executionApproach: ExecutionApproach,

    /**
     * Fulfillment type - distinguishes between task-based and quantity-based fulfillment
     * Default: STANDARD_TASK_BASED for backward compatibility with existing records
     */
    val fulfillmentType: FulfillmentType = FulfillmentType.STANDARD_TASK_BASED,

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

    /**
     * Quantity source details - populated for CONTAINER_QUANTITY_BASED and LOCATION_QUANTITY_BASED
     * Null for STANDARD_TASK_BASED (uses allocatedItems in lineItems instead)
     */
    val quantitySourceDetails: QuantitySourceDetails? = null,

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

    // Loading documents (for direct processing)
    val loadingDocuments: LoadingDocuments? = null,

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

    /**
     * Allocated items for STANDARD_TASK_BASED fulfillment (Scenario 1)
     * References StorageItem records
     */
    val allocatedItems: MutableList<AllocatedItem> = mutableListOf(),

    /**
     * Quantity inventory references for CONTAINER_QUANTITY_BASED and LOCATION_QUANTITY_BASED fulfillment (Scenarios 2 & 3)
     * References QuantityBasedInventory records
     * Empty for STANDARD_TASK_BASED (uses allocatedItems instead)
     */
    val quantityInventoryReferences: MutableList<QuantityInventoryReference> = mutableListOf()
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
    val itemBarcode: String
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
    val notes: String? = null,
    val currentStatus: Boolean = false
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

    // GIN Email Form Details
    val ginDate: LocalDateTime? = null,        // Business date for GIN document
    val toEmail: String? = null,               // Primary recipient email
    val ccEmails: List<String> = listOf(),     // CC recipient emails
    val subject: String? = null,               // Email subject line
    val emailContent: String? = null,          // Email body content

    // Signed GIN Copy
    val signedGINCopy: String? = null,         // URL/path to signed GIN document

    // Attachments
    val attachments: MutableList<GinAttachment> = mutableListOf()  // GIN attachments (PDF, signed docs, etc.)
) {
    /**
     * Adds or updates an attachment in the attachments list.
     * If an attachment with the same fileName exists, it will be replaced.
     * Otherwise, a new attachment will be added.
     *
     * @param attachment The attachment to add or update
     * @return The updated GinNotification instance
     */
    fun addOrUpdateAttachment(attachment: GinAttachment): GinNotification {
        val existingIndex = attachments.indexOfFirst { it.fileName == attachment.fileName }
        if (existingIndex >= 0) {
            attachments[existingIndex] = attachment
        } else {
            attachments.add(attachment)
        }
        return this
    }

    /**
     * Adds or updates an attachment by fileName and fileUrl.
     *
     * @param fileName The name of the file
     * @param fileUrl The URL/path of the file
     * @return The updated GinNotification instance
     */
    fun addOrUpdateAttachment(fileName: String, fileUrl: String): GinNotification {
        return addOrUpdateAttachment(GinAttachment(fileName, fileUrl))
    }
}

data class GinAttachment(
    val fileName: String,
    val fileUrl: String  // S3 URL, local path, or Base64 encoded string
)

data class LoadingDocuments(
    val packagePhotosUrls: List<String> = listOf(),
    val truckDriverPhotoUrl: String? = null,
    val truckDriverIdProofUrl: String? = null
)

// Quantity-based fulfillment data classes (Scenarios 2 & 3)

/**
 * Quantity source details for CONTAINER_QUANTITY_BASED and LOCATION_QUANTITY_BASED fulfillment
 * Contains detailed information about where quantities were picked from
 */
data class QuantitySourceDetails(
    val itemSources: List<ItemSource> = listOf()
)

/**
 * Individual item source - represents one SKU or quantity inventory item with its sources
 */
data class ItemSource(
    // For CONTAINER_QUANTITY_BASED (Scenario 2)
    val skuId: Long? = null,                                // SKU identifier
    val totalQuantityPicked: Int? = null,                   // Total picked across all sources

    // For LOCATION_QUANTITY_BASED (Scenario 3)
    val quantityInventoryId: String? = null,                // QBI ID (e.g., "QBI-100")
    val itemType: ItemType? = null,                         // PALLET or BOX
    val description: String? = null,                        // Freetext description

    // Common - source details
    val containerSources: List<ContainerSource>? = null,    // For Scenario 2
    val locationSources: List<LocationSource>? = null       // For Scenario 3
)

/**
 * Container source - for Scenario 2 (container-based picking)
 */
data class ContainerSource(
    val containerBarcode: String,                           // Container barcode
    val quantityInventoryId: String,                        // QBI ID
    val quantityPicked: Int,                                // Quantity from this container
    val locationCode: String                                // Physical location
)

/**
 * Location source - for Scenario 3 (location-based picking)
 */
data class LocationSource(
    val locationCode: String,                               // Physical location
    val quantityPicked: Int                                 // Quantity from this location
)

/**
 * Quantity inventory reference - tracks which QuantityBasedInventory records were used for a line item
 * Used in LineItem for CONTAINER_QUANTITY_BASED and LOCATION_QUANTITY_BASED fulfillment types
 */
data class QuantityInventoryReference(
    val quantityInventoryId: String,                        // QBI ID (e.g., "QBI-001")
    val quantityShipped: Int,                               // How much shipped from this QBI
    val containerBarcode: String? = null,                   // If from Scenario 2
    val locationCode: String? = null,                       // If from Scenario 3
    val transactionId: String                               // QuantityTransaction ID created
)
