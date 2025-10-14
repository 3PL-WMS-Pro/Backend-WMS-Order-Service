package com.wmspro.order.dto

import java.time.LocalDateTime

/**
 * OFR Detail Page DTOs
 * These DTOs support the OFR detail page with header and multiple tabs
 */

// ========== API 108: OFR Header Response ==========

/**
 * API 108: OFR Header Response
 * Common header information displayed across all tabs
 */
data class OfrHeaderResponse(
    val fulfillmentId: String,
    val notificationSource: String,  // FulfillmentSource display name
    val customer: String?,           // Account name from Account Service
    val ginNumber: String?,
    val createdDate: LocalDateTime
)

// ========== API 109: Picking Details Tab Response ==========

/**
 * API 109: Picking Details Tab Response
 */
data class PickingDetailsTabResponse(
    val taskInfo: PickingTaskInfo,
    val items: List<PickingItemDetail>
)

data class PickingTaskInfo(
    val taskCode: String,
    val createdOn: LocalDateTime,
    val executiveName: String?,  // Full name from User Service
    val items: Int,              // Count of itemsToPick
    val locations: Int           // Count of distinct pickedFromLocation in itemsPicked
)

data class PickingItemDetail(
    val serialNumber: Int,
    val itemId: String,          // SKU code, Box barcode, or Pallet barcode
    val itemName: String?,       // SKU product title, "Box", or "Pallet"
    val itemImage: String?,      // Primary SKU image (null for Box/Pallet)
    val itemBarcode: String?,    // For navigation/linking
    val skuId: Long?,            // For SKU items
    val pickingLocation: String  // From itemsPicked.pickedFromLocation
)

// ========== API 110: Pack Move Pending Tab Response ==========

/**
 * API 110: Pack Move Pending Tab Response
 */
data class PackMovePendingTabResponse(
    val taskInfo: PackMoveTaskInfo,
    val items: List<PackMoveItemDetail>
)

data class PackMoveTaskInfo(
    val taskCode: String,
    val createdOn: LocalDateTime,
    val executiveName: String?,  // Full name from User Service
    val items: Int,              // Count of itemsToVerify
    val packingZones: Int        // Count of distinct packing zones from Picking Task
)

data class PackMoveItemDetail(
    val serialNumber: Int,
    val itemId: String,          // SKU code, Box barcode, or Pallet barcode
    val itemName: String?,       // SKU product title, "Box", or "Pallet"
    val itemImage: String?,      // Primary SKU image (null for Box/Pallet)
    val itemBarcode: String?,    // For navigation/linking
    val skuId: Long?,            // For SKU items
    val packingZone: String      // From Picking Task's packingZoneVisits
)

// ========== API 111: Pick Pack Move Details Tab Response ==========

/**
 * API 111: Pick Pack Move Details Tab Response
 */
data class PickPackMoveDetailsTabResponse(
    val taskInfo: PickPackMoveTaskInfo,
    val items: List<PickPackMoveItemDetail>
)

data class PickPackMoveTaskInfo(
    val taskCode: String,
    val startedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    val durationMinutes: Int?,
    val executiveName: String?,  // Full name from User Service
    val items: Int,              // Count of itemsToPick
    val locations: Int           // Count of distinct locations
)

data class PickPackMoveItemDetail(
    val serialNumber: Int,
    val itemId: String,          // SKU code, Box barcode, or Pallet barcode
    val itemName: String?,       // SKU product title, "Box", or "Pallet"
    val itemImage: String?,      // Primary SKU image (null for Box/Pallet)
    val itemBarcode: String?,    // For navigation/linking
    val skuId: Long?,            // For SKU items
    val pickingLocation: String? // From pickupLocations
    // TODO: This implementation will need to change after PickPackMove task model is updated
)

// ========== API 112: Ready To Dispatch Tab Response ==========

/**
 * API 112: Ready To Dispatch Tab Response
 */
data class ReadyToDispatchTabResponse(
    val taskInfo: ReadyToDispatchInfo,
    val packages: List<PackageDetail>
)

data class ReadyToDispatchInfo(
    val taskCode: String,        // Pick Pack Move or Pack Move task code
    val executiveName: String?,  // Full name from User Service
    val awbPrinted: Boolean,     // shippingDetails.awbPdf != null
    val awbNumber: String?,      // AWB number if available
    val packages: Int            // Count of packages
)

data class PackageDetail(
    val serialNumber: Int,
    val packageBarCode: String?,
    val dimension: String?,      // Format: "40×30×20"
    val weight: String?,         // Format: "5.5KG"
    val items: Int,              // Count of assignedItems
    val dispatchArea: String?,   // Dispatch area location
    val assignedItems: List<PackageItemDetail>  // Items inside package
)

data class PackageItemDetail(
    val serialNumber: Int,
    val itemId: String,          // SKU code, Box barcode, or Pallet barcode
    val itemName: String?,       // SKU product title, "Box", or "Pallet"
    val itemImage: String?,      // Primary SKU image (null for Box/Pallet)
    val itemBarcode: String?,    // For navigation/linking
    val skuId: Long?             // For SKU items
)

// ========== API 113: Loading Tab Response ==========

/**
 * API 113: Loading Done & GIN Pending Tab Response
 */
data class LoadingTabResponse(
    val taskInfo: LoadingTaskInfo,
    val truckDetails: TruckDetails,
    val packages: List<PackageDetail>  // Reuses PackageDetail from Ready To Dispatch
)

data class LoadingTaskInfo(
    val taskCode: String,
    val createdOn: LocalDateTime,
    val executiveName: String?  // Full name from User Service
)

data class TruckDetails(
    val vehicleNumber: String?,
    val signedGin: String?,          // URL to view signed GIN document
    val driverPhotoProof: String?,   // URL to view driver photo
    val driverIdentityProof: String?,// URL to view driver identity proof
    val placementPhotos: List<String>?  // Array of photo URLs
)

// ========== API 114: GIN Sent Tab Response ==========

/**
 * API 114: GIN Sent Tab Response
 */
data class GinSentTabResponse(
    val ginInfo: GinInfo,
    val ginForm: GinFormDetails,
    val packageDetails: List<PackageDetail>,  // Reuses PackageDetail
    val attachments: List<GinAttachmentDto>
)

data class GinInfo(
    val completedAt: LocalDateTime?,
    val ginNumber: String?,
    val totalItems: Int,         // Sum of all package items
    val emailStatus: String      // "Success" if sentToCustomer, else "Pending"/"Failed"
)

data class GinFormDetails(
    val ginDate: LocalDateTime?,
    val toEmail: String?,
    val ccEmails: List<String>,
    val subject: String?,
    val emailContent: String?
)

data class GinAttachmentDto(
    val fileName: String,
    val fileUrl: String
)
