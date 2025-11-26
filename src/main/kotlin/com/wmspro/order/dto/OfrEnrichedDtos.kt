package com.wmspro.order.dto

import com.wmspro.order.enums.*
import com.wmspro.order.model.*
import java.time.LocalDateTime

/**
 * Comprehensive enriched OFR response with data from multiple microservices
 * Includes enriched details from:
 * - Product Service (SKU details)
 * - Inventory Service (Storage Item details, Quantity Inventory details)
 * - Account Service (Account/Customer names)
 * - User Service (User details)
 */
data class OfrEnrichedResponse(
    // Basic OFR Information
    val fulfillmentId: String,
    val accountId: Long,
    val accountName: String?, // ENRICHED from Account Service
    val fulfillmentSource: FulfillmentSource,
    val fulfillmentType: FulfillmentType,
    val externalOrderId: String?,
    val externalOrderNumber: String?,
    val fulfillmentStatus: FulfillmentStatus,
    val priority: Priority,
    val executionApproach: ExecutionApproach,

    // QC Details
    val qcDetails: QcDetails?,

    // Task IDs (Task Service data excluded as per requirement)
    val pickingTaskId: String?,
    val packMoveTaskId: String?,
    val pickPackMoveTaskId: String?,
    val loadingTaskId: String?,

    // Customer Information
    val customerInfo: CustomerInfo,

    // Shipping Address
    val shippingAddress: ShippingAddress,

    // ENRICHED Line Items
    val lineItems: List<LineItemEnriched>,

    // ENRICHED Packages
    val packages: List<PackageEnriched>,

    // Quantity Source Details (for quantity-based fulfillment)
    val quantitySourceDetails: QuantitySourceDetailsEnriched?,

    // Shipping Details
    val shippingDetails: ShippingDetails?,

    // Status Tracking
    val statusHistory: List<StatusHistory>,

    val fulfillmentRequestDate: LocalDateTime,

    // Financial Summary
    val orderValue: OrderValue?,

    // GIN Management
    val ginNumber: String?,
    val ginNotification: GinNotification?,

    // Loading Documents
    val loadingDocuments: LoadingDocuments?,

    val notes: String?,
    val tags: List<String>,
    val customFields: Map<String, Any>,

    val cancelledAt: LocalDateTime?,
    val cancellationReason: String?,

    // ENRICHED Audit Fields
    val createdBy: String?,
    val createdByUserName: String?, // ENRICHED from User Service
    val updatedBy: String?,
    val updatedByUserName: String?, // ENRICHED from User Service

    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

/**
 * Enriched Line Item with SKU and Storage Item details
 */
data class LineItemEnriched(
    val lineItemId: String,
    val itemType: ItemType,

    // SKU Information (ENRICHED)
    val skuId: Long?,
    val skuCode: String?, // ENRICHED from Product Service
    val productTitle: String?, // ENRICHED from Product Service
    val productImageUrl: String?, // ENRICHED from Product Service

    // Item Barcode
    val itemBarcode: String?,
    val allocationMethod: AllocationMethod?,

    // Quantities
    val quantityOrdered: Int,
    val quantityPicked: Int,
    val quantityShipped: Int,

    // ENRICHED Allocated Items (for STANDARD_TASK_BASED)
    val allocatedItems: List<AllocatedItemEnriched>,

    // ENRICHED Quantity Inventory References (for CONTAINER/LOCATION quantity-based)
    val quantityInventoryReferences: List<QuantityInventoryReferenceEnriched>
)

/**
 * Enriched Allocated Item with Storage Item details
 */
data class AllocatedItemEnriched(
    val storageItemId: Long,
    val location: String,

    // ENRICHED Storage Item Details from Inventory Service
    val itemBarcode: String?,
    val itemType: String?,
    val skuId: Long?,
    val lengthCm: Double?,
    val widthCm: Double?,
    val heightCm: Double?,
    val volumeCbm: Double?,
    val weightKg: Double?,
    val parentItemBarcode: String?,

    // Picking Tracking
    val picked: Boolean,
    val pickedAt: LocalDateTime?,
    val pickedBy: String?
)

/**
 * Enriched Quantity Inventory Reference with full QBI details
 */
data class QuantityInventoryReferenceEnriched(
    val quantityInventoryId: String,
    val quantityShipped: Int,
    val containerBarcode: String?,
    val locationCode: String?,
    val transactionId: String,

    // ENRICHED Quantity Inventory Details from Inventory Service
    val itemType: String?,
    val totalQuantity: Int?,
    val availableQuantity: Int?,
    val description: String?
)

/**
 * Enriched Package with Assigned Item details
 */
data class PackageEnriched(
    val packageId: String,
    val packageBarcode: String?,

    // Package Physical Details
    val dimensions: PackageDimensions?,
    val weight: PackageWeight?,

    // ENRICHED Assigned Items
    val assignedItems: List<AssignedItemEnriched>,

    // Dispatch Tracking
    val dispatchArea: String?,
    val dispatchAreaBarcode: String?,
    val droppedAtDispatch: Boolean,
    val dispatchScannedAt: LocalDateTime?,

    // Package Lifecycle
    val createdAt: LocalDateTime,
    val createdByTask: String?,
    val savedAt: LocalDateTime?,

    // Shipping Details
    val loadedOnTruck: Boolean,
    val truckNumber: String?,
    val loadedAt: LocalDateTime?
)

/**
 * Enriched Assigned Item with SKU and Storage Item details
 */
data class AssignedItemEnriched(
    val storageItemId: Long,
    val skuId: Long?,
    val itemType: ItemType,
    val itemBarcode: String,

    // ENRICHED SKU Details from Product Service
    val skuCode: String?,
    val productTitle: String?,
    val productImageUrl: String?,

    // ENRICHED Storage Item Details from Inventory Service
    val lengthCm: Double?,
    val widthCm: Double?,
    val heightCm: Double?,
    val volumeCbm: Double?,
    val weightKg: Double?
)

/**
 * Enriched Quantity Source Details
 */
data class QuantitySourceDetailsEnriched(
    val itemSources: List<ItemSourceEnriched>
)

/**
 * Enriched Item Source with SKU and QBI details
 */
data class ItemSourceEnriched(
    // For CONTAINER_QUANTITY_BASED (Scenario 2)
    val skuId: Long?,
    val skuCode: String?, // ENRICHED from Product Service
    val productTitle: String?, // ENRICHED from Product Service
    val totalQuantityPicked: Int?,

    // For LOCATION_QUANTITY_BASED (Scenario 3)
    val quantityInventoryId: String?,
    val itemType: ItemType?,
    val description: String?,

    // Common - source details
    val containerSources: List<ContainerSourceEnriched>?,
    val locationSources: List<LocationSource>?
)

/**
 * Enriched Container Source with QBI details
 */
data class ContainerSourceEnriched(
    val containerBarcode: String,
    val quantityInventoryId: String,
    val quantityPicked: Int,
    val locationCode: String,

    // ENRICHED Quantity Inventory Details from Inventory Service
    val itemType: String?,
    val totalQuantity: Int?,
    val availableQuantity: Int?,
    val description: String?
)

/**
 * Enrichment metadata - tracks what was successfully enriched
 */
data class EnrichmentMetadata(
    val accountEnriched: Boolean,
    val usersEnriched: Boolean,
    val skusEnriched: Int,
    val storageItemsEnriched: Int,
    val quantityInventoriesEnriched: Int,
    val enrichmentTimestamp: LocalDateTime = LocalDateTime.now()
)
