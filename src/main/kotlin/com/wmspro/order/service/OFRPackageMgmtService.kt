package com.wmspro.order.service

import com.wmspro.order.dto.*
import com.wmspro.order.enums.ItemType
import com.wmspro.order.exception.InvalidOrderRequestException
import com.wmspro.order.exception.OrderFulfillmentRequestNotFoundException
import com.wmspro.order.model.AssignedItem
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for OFR Package Management (Phase 6.2)
 * Handles all package-related operations: create, read, update, and dispatch
 */
@Service
@Transactional
class OFRPackageMgmtService(
    private val ofrRepository: OrderFulfillmentRequestRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * API 147: Get All Packages (For a particular OFR)
     * Returns package summaries with specific fields for all created packages
     */
    fun getAllPackages(fulfillmentId: String): List<PackageSummaryDto> {
        logger.info("API 147: Get all packages for OFR: {}", fulfillmentId)

        val ofr = ofrRepository.findByFulfillmentId(fulfillmentId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentId") }

        if (ofr.packages.isEmpty()) {
            logger.info("No packages found for OFR: {}", fulfillmentId)
            return emptyList()
        }

        return ofr.packages.map { pkg ->
            PackageSummaryDto(
                packageId = pkg.packageId,
                packageBarcode = pkg.packageBarcode,
                dimensions = pkg.dimensions,
                weight = pkg.weight,
                noOfItems = pkg.assignedItems.size
            )
        }
    }

    /**
     * Get Package By ID
     * Returns detailed package information for a specific package
     */
    fun getPackageById(fulfillmentId: String, packageId: String): com.wmspro.order.model.Package {
        logger.info("Get package by ID: {} for OFR: {}", packageId, fulfillmentId)

        val ofr = ofrRepository.findByFulfillmentId(fulfillmentId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentId") }

        return ofr.packages.find { it.packageId == packageId }
            ?: throw InvalidOrderRequestException("Package not found: $packageId")
    }

    /**
     * API 149: Create New Package
     * Creates package in OFR with full validation including barcode uniqueness,
     * item assignment prevention, and quantity validation
     */
    fun createPackage(fulfillmentId: String, request: CreatePackageRequest): PackageResponse {
        logger.info("API 149: Creating new package for OFR: {}", fulfillmentId)

        // Step 1: Fetch OFR
        val ofr = ofrRepository.findByFulfillmentId(fulfillmentId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentId") }

        // Step 2: Validate required fields
        validatePackageRequiredFields(request)

        // Step 3: Validate package barcode uniqueness across ALL OFRs
        if (ofrRepository.existsByPackagesPackageBarcode(request.packageBarcode)) {
            logger.error("Package barcode already exists: {}", request.packageBarcode)
            throw InvalidOrderRequestException("Package barcode already exists: ${request.packageBarcode}")
        }

        // Step 4: Validate storage items not already assigned to another package
        validateStorageItemsNotAssigned(ofr, request.assignedItems, null)

        // Step 5: Validate quantities for SKU items
        validateSkuItemQuantities(ofr, request.assignedItems, null)

        // Step 6: Generate package ID
        val packageId = java.util.UUID.randomUUID().toString()

        // Step 7: Create package object
        val newPackage = com.wmspro.order.model.Package(
            packageId = packageId,
            packageBarcode = request.packageBarcode,
            dimensions = request.dimensions,
            weight = request.weight,
            assignedItems = request.assignedItems.map { item ->
                AssignedItem(
                    storageItemId = item.storageItemId,
                    skuId = item.skuId,
                    itemType = item.itemType,
                    itemBarcode = item.itemBarcode
                )
            }.toMutableList(),
            dispatchArea = null,
            dispatchAreaBarcode = null,
            droppedAtDispatch = false,
            dispatchScannedAt = null,
            createdAt = LocalDateTime.now(),
            createdByTask = request.createdByTask,
            savedAt = LocalDateTime.now(),
            loadedOnTruck = false,
            truckNumber = null,
            loadedAt = null
        )

        // Step 8: Add package to OFR
        ofr.packages.add(newPackage)

        // Step 9: Save OFR
        ofrRepository.save(ofr)
        logger.info("Package created successfully: {}", packageId)

        return PackageResponse(
            packageId = newPackage.packageId,
            packageBarcode = newPackage.packageBarcode,
            dimensions = newPackage.dimensions,
            weight = newPackage.weight,
            assignedItems = newPackage.assignedItems,
            createdAt = newPackage.createdAt,
            createdByTask = newPackage.createdByTask,
            savedAt = newPackage.savedAt
        )
    }

    /**
     * API 151: Update Existing Package
     * Updates package with restrictions (cannot update if dropped or loaded)
     */
    fun updatePackage(
        fulfillmentId: String,
        packageId: String,
        request: UpdatePackageRequest
    ): PackageResponse {
        logger.info("API 151: Updating package {} for OFR: {}", packageId, fulfillmentId)

        // Step 1: Fetch OFR
        val ofr = ofrRepository.findByFulfillmentId(fulfillmentId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentId") }

        // Step 2: Find package in packages array
        val packageIndex = ofr.packages.indexOfFirst { it.packageId == packageId }
        if (packageIndex == -1) {
            logger.error("Package not found: {}", packageId)
            throw InvalidOrderRequestException("Package not found: $packageId")
        }

        val existingPackage = ofr.packages[packageIndex]

        // Step 3: Validate package not already dropped or loaded
        if (existingPackage.droppedAtDispatch) {
            logger.error("Cannot update package - already at dispatch area: {}", packageId)
            throw InvalidOrderRequestException("Cannot update package - already at dispatch area")
        }

        if (existingPackage.loadedOnTruck) {
            logger.error("Cannot update package - already loaded on truck: {}", packageId)
            throw InvalidOrderRequestException("Cannot update package - already loaded on truck")
        }

        // Step 4: Validate package barcode uniqueness if being updated (exclude current package)
        if (request.packageBarcode != null && request.packageBarcode != existingPackage.packageBarcode) {
            // Check if new barcode exists in other packages in this OFR
            val barcodeExistsInOtherPackages = ofr.packages.any {
                it.packageId != packageId && it.packageBarcode == request.packageBarcode
            }
            if (barcodeExistsInOtherPackages) {
                logger.error("Package barcode already exists in this OFR: {}", request.packageBarcode)
                throw InvalidOrderRequestException("Package barcode already exists in this OFR: ${request.packageBarcode}")
            }

            // Check if barcode exists in other OFRs
            if (ofrRepository.existsByPackagesPackageBarcode(request.packageBarcode)) {
                logger.error("Package barcode already exists in another OFR: {}", request.packageBarcode)
                throw InvalidOrderRequestException("Package barcode already exists: ${request.packageBarcode}")
            }
        }

        // Step 5: Validate assigned items if being updated
        if (request.assignedItems != null) {
            validateStorageItemsNotAssigned(ofr, request.assignedItems, packageId)
            validateSkuItemQuantities(ofr, request.assignedItems, packageId)
        }

        // Step 6: Update package using copy() method (immutable data class)
        val updatedPackage = existingPackage.copy(
            packageBarcode = request.packageBarcode ?: existingPackage.packageBarcode,
            dimensions = request.dimensions ?: existingPackage.dimensions,
            weight = request.weight ?: existingPackage.weight,
            assignedItems = if (request.assignedItems != null) {
                request.assignedItems.map { item ->
                    AssignedItem(
                        storageItemId = item.storageItemId,
                        skuId = item.skuId,
                        itemType = item.itemType,
                        itemBarcode = item.itemBarcode
                    )
                }.toMutableList()
            } else {
                existingPackage.assignedItems
            },
            savedAt = LocalDateTime.now()
        )

        // Step 7: Replace package in array
        ofr.packages[packageIndex] = updatedPackage

        // Step 8: Save OFR
        ofrRepository.save(ofr)
        logger.info("Package updated successfully: {}", packageId)

        return PackageResponse(
            packageId = updatedPackage.packageId,
            packageBarcode = updatedPackage.packageBarcode,
            dimensions = updatedPackage.dimensions,
            weight = updatedPackage.weight,
            assignedItems = updatedPackage.assignedItems,
            createdAt = updatedPackage.createdAt,
            createdByTask = updatedPackage.createdByTask,
            savedAt = updatedPackage.savedAt
        )
    }

    /**
     * API 157: Drop Packages at Dispatch
     * Marks packages as dropped in dispatch area (batch operation)
     */
    fun dropPackagesAtDispatch(fulfillmentId: String, request: DropPackagesRequest): OrderFulfillmentRequest {
        logger.info("API 157: Dropping {} packages at dispatch for OFR: {}",
            request.droppedPackageBarcodes.size, fulfillmentId)

        // Step 1: Fetch OFR
        val ofr = ofrRepository.findByFulfillmentId(fulfillmentId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentId") }

        // Step 2: Update matching packages
        var updatedCount = 0
        ofr.packages.forEachIndexed { index, pkg ->
            if (request.droppedPackageBarcodes.contains(pkg.packageBarcode)) {
                // Update package with dispatch information using copy()
                ofr.packages[index] = pkg.copy(
                    dispatchArea = request.dispatchZoneBarcode,
                    dispatchAreaBarcode = request.dispatchZoneBarcode,
                    droppedAtDispatch = true,
                    dispatchScannedAt = LocalDateTime.now(),
                    savedAt = LocalDateTime.now()
                )
                updatedCount++
                logger.info("Package marked as dropped at dispatch: {}", pkg.packageBarcode)
            }
        }

        // Step 3: Log warning if some barcodes not found
        if (updatedCount < request.droppedPackageBarcodes.size) {
            logger.warn("Some package barcodes not found in OFR. Expected: {}, Updated: {}",
                request.droppedPackageBarcodes.size, updatedCount)
        }

        // Step 4: Save OFR
        val updatedOfr = ofrRepository.save(ofr)
        logger.info("Packages dropped at dispatch successfully. Updated {} packages", updatedCount)

        return updatedOfr
    }

    // ========== PACKAGE VALIDATION HELPER METHODS ==========

    /**
     * Validate all required fields in create package request
     */
    private fun validatePackageRequiredFields(request: CreatePackageRequest) {
        // Validate dimensions
        if (request.dimensions.length <= 0 || request.dimensions.width <= 0 || request.dimensions.height <= 0) {
            throw InvalidOrderRequestException("Package dimensions must be positive numbers")
        }

        // Validate weight
        if (request.weight.value <= 0) {
            throw InvalidOrderRequestException("Package weight must be a positive number")
        }

        // Validate assigned items not empty
        if (request.assignedItems.isEmpty()) {
            throw InvalidOrderRequestException("Package must have at least one assigned item")
        }

        // Validate each assigned item
        request.assignedItems.forEach { item ->
            when (item.itemType) {
                ItemType.SKU_ITEM -> {
                    if (item.skuId == null) {
                        throw InvalidOrderRequestException("SKU ID is required for SKU_ITEM type")
                    }
                }
                ItemType.BOX, ItemType.PALLET -> {
                    if (item.itemBarcode.isBlank()) {
                        throw InvalidOrderRequestException("Item barcode is required for ${item.itemType} type")
                    }
                }
            }

            // Validate itemBarcode is not blank for all types
            if (item.itemBarcode.isBlank()) {
                throw InvalidOrderRequestException("Item barcode cannot be blank")
            }
        }
    }

    /**
     * Validate storage items are not already assigned to another package
     */
    private fun validateStorageItemsNotAssigned(
        ofr: OrderFulfillmentRequest,
        assignedItems: List<AssignedItemDto>,
        excludePackageId: String?
    ) {
        assignedItems.forEach { item ->
            ofr.packages.forEach { pkg ->
                // Skip current package if updating
                if (excludePackageId != null && pkg.packageId == excludePackageId) {
                    return@forEach
                }

                // Check if storage item already assigned
                val alreadyAssigned = pkg.assignedItems.any { it.storageItemId == item.storageItemId }
                if (alreadyAssigned) {
                    logger.error("Item {} already assigned to package {}", item.storageItemId, pkg.packageId)
                    throw InvalidOrderRequestException(
                        "Item ${item.storageItemId} already assigned to package ${pkg.packageId}"
                    )
                }
            }
        }
    }

    /**
     * Validate SKU item quantities don't exceed available quantity
     * Note: Each AssignedItem now represents one physical item (no quantity field)
     */
    private fun validateSkuItemQuantities(
        ofr: OrderFulfillmentRequest,
        assignedItems: List<AssignedItemDto>,
        excludePackageId: String?
    ) {
        // Group assigned items to add by SKU ID and count them
        val skuItemsToAdd = assignedItems.filter { it.itemType == ItemType.SKU_ITEM && it.skuId != null }
        val skuCountsToAdd = skuItemsToAdd.groupBy { it.skuId }.mapValues { it.value.size }

        skuCountsToAdd.forEach { (skuId, countToAdd) ->
            // Calculate already packaged count for this SKU (excluding current package if updating)
            val alreadyPackaged = ofr.packages
                .filter { pkg -> excludePackageId == null || pkg.packageId != excludePackageId }
                .flatMap { it.assignedItems }
                .count { it.itemType == ItemType.SKU_ITEM && it.skuId == skuId }

            // Find line item for this SKU
            val lineItem = ofr.lineItems.find { it.itemType == ItemType.SKU_ITEM && it.skuId == skuId }
                ?: throw InvalidOrderRequestException("Line item not found for SKU ID: $skuId")

            // Calculate total packaged (existing + new)
            val totalPackaged = alreadyPackaged + countToAdd

            // Validate against picked quantity
            if (totalPackaged > lineItem.quantityPicked) {
                logger.error("Quantity exceeds available for SKU {}. Available: {}, Requested total: {}",
                    skuId, lineItem.quantityPicked, totalPackaged)
                throw InvalidOrderRequestException(
                    "Quantity exceeds available for SKU $skuId. Available: ${lineItem.quantityPicked}, Already packaged: $alreadyPackaged, Requesting: $countToAdd"
                )
            }
        }
    }
}
