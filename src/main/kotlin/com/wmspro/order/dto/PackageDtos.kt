package com.wmspro.order.dto

import com.wmspro.order.enums.ItemType
import com.wmspro.order.model.AssignedItem
import com.wmspro.order.model.PackageDimensions
import com.wmspro.order.model.PackageWeight
import java.time.LocalDateTime

/**
 * API 147: Get All Packages Response
 */
data class PackageSummaryDto(
    val packageId: String,
    val packageBarcode: String?,
    val dimensions: PackageDimensions?,
    val weight: PackageWeight?,
    val noOfItems: Int
)

/**
 * API 149: Create New Package Request
 */
data class CreatePackageRequest(
    val dimensions: PackageDimensions,
    val weight: PackageWeight,
    val assignedItems: List<AssignedItemDto>,
    val packageBarcode: String,
    val createdByTask: String
)

/**
 * API 149: Assigned Item DTO for Request
 */
data class AssignedItemDto(
    val storageItemId: Long,
    val skuId: Long?,
    val itemType: ItemType,
    val itemBarcode: String
)

/**
 * API 149: Create Package Response
 */
data class PackageResponse(
    val packageId: String,
    val packageBarcode: String?,
    val dimensions: PackageDimensions?,
    val weight: PackageWeight?,
    val assignedItems: List<AssignedItem>,
    val createdAt: LocalDateTime,
    val createdByTask: String?,
    val savedAt: LocalDateTime?
)

/**
 * API 151: Update Existing Package Request
 */
data class UpdatePackageRequest(
    val dimensions: PackageDimensions?,
    val weight: PackageWeight?,
    val assignedItems: List<AssignedItemDto>?,
    val packageBarcode: String?
)

/**
 * API 157: Drop Packages at Dispatch Request
 */
data class DropPackagesRequest(
    val dispatchZoneBarcode: String,
    val droppedPackageBarcodes: List<String>
)
