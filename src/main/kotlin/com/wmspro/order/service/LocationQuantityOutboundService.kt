package com.wmspro.order.service

import com.wmspro.order.client.*
import com.wmspro.order.dto.*
import com.wmspro.order.enums.*
import com.wmspro.order.model.*
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import com.wmspro.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Service for handling location-based quantity outbound fulfillment (Scenario 3)
 *
 * This service implements post-facto recording where:
 * - Bulk items (pallets/boxes) have been physically picked from locations
 * - No barcodes involved (pure location-based tracking)
 * - Items have already been packed/loaded
 * - System records what was done and reduces inventory with location updates
 */
@Service
@Transactional
class LocationQuantityOutboundService(
    private val orderFulfillmentRequestRepository: OrderFulfillmentRequestRepository,
    private val quantityInventoryClient: QuantityInventoryClient,
    private val quantityTransactionClient: QuantityTransactionClient,
    private val barcodeReservationClient: BarcodeReservationClient,
    private val sequenceGeneratorService: SequenceGeneratorService,
    private val orderFulfillmentService: OrderFulfillmentService
) {
    private val logger = LoggerFactory.getLogger(LocationQuantityOutboundService::class.java)
    private val dateFormatter = DateTimeFormatter.ISO_DATE_TIME

    /**
     * Create OrderFulfillmentRequest with location-based quantity picking
     *
     * Implements 9-step workflow from backend implementation guide:
     * 1. Validate Request
     * 2. Check Inventory Availability at Locations
     * 3. Reduce Inventory with Location Updates
     * 4. Consume Package Barcodes
     * 5. Generate IDs
     * 6. Build OrderFulfillmentRequest
     * 7. Generate AWB (If Requested)
     * 8. Save OrderFulfillmentRequest
     * 9. Build Response
     */
    fun createOFR(request: CreateLocationQuantityBasedRequest, user: String): LocationQuantityBasedOFRResponse {
        val tenantId = TenantContext.requireCurrentTenant()

        logger.info("Creating location-based quantity OFR for accountId=${request.accountId}, tenant=$tenantId")

        // STEP 1: Validate Request
        logger.debug("Step 1: Validating request")
        validateRequest(request)

        // STEP 2: Check Inventory Availability at Locations
        logger.debug("Step 2: Checking inventory availability at locations")
        val inventoryData = checkInventoryAvailability(request)

        // STEP 3: Reduce Inventory with Location Updates
        logger.debug("Step 3: Reducing inventory with location updates")
        val inventoryReductions = reduceInventoryWithLocations(request, inventoryData, user)

        // STEP 4: Consume Package Barcodes
        logger.debug("Step 4: Consuming package barcodes")
        consumePackageBarcodes(request.packages.map { it.packageBarcode })

        // STEP 5: Generate IDs
        logger.debug("Step 5: Generating IDs")
        val fulfillmentId = sequenceGeneratorService.generateOfrId()
        val ginNumber = sequenceGeneratorService.generateGinNumber()

        // STEP 6: Create Quantity Transactions (with location changes)
        logger.debug("Step 6: Creating quantity transactions with location changes")
        val transactionIds = createQuantityTransactionsWithLocations(request, inventoryReductions, fulfillmentId, user)

        // STEP 7: Build OrderFulfillmentRequest
        logger.debug("Step 7: Building OrderFulfillmentRequest")
        val ofr = buildOrderFulfillmentRequest(request, fulfillmentId, ginNumber, inventoryReductions, transactionIds, user)

        // STEP 8: Save OrderFulfillmentRequest
        logger.debug("Step 8: Saving OrderFulfillmentRequest")
        var savedOFR = orderFulfillmentRequestRepository.save(ofr)

        // STEP 9: Generate AWB (If Requested)
        logger.debug("Step 9: Generating AWB (if requested)")
        var awbNumber: String? = null
        var awbPdf: String? = null
        var trackingUrl: String? = null

        if (request.shippingDetails.awbCondition == AwbCondition.CREATE_FOR_CUSTOMER) {
            logger.info("Generating AWB for OFR: $fulfillmentId")
            val awbResponse = orderFulfillmentService.createAwbForAllPackages(fulfillmentId)

            awbNumber = awbResponse.awbNumber
            awbPdf = awbResponse.awbPdf
            trackingUrl = awbResponse.trackingUrl

            // Fetch updated OFR (createAwbForAllPackages saves the updated OFR with AWB details)
            savedOFR = orderFulfillmentRequestRepository.findByFulfillmentId(fulfillmentId)
                .orElse(savedOFR)
        }

        logger.info("Successfully created location-based quantity OFR: $fulfillmentId, GIN: $ginNumber")

        // STEP 10: Build Response
        return buildResponse(savedOFR, inventoryReductions, transactionIds, awbNumber, awbPdf, trackingUrl)
    }

    /**
     * STEP 1: Validate Request
     * Validates all aspects of the request
     */
    private fun validateRequest(request: CreateLocationQuantityBasedRequest) {
        // Validate item types are PALLET or BOX only (no SKU_ITEM)
        for (item in request.itemsPicked) {
            if (item.itemType !in listOf("PALLET", "BOX")) {
                throw IllegalArgumentException(
                    "Invalid item type for location-based outbound: ${item.itemType}. " +
                    "Only PALLET or BOX allowed. Use container-based API for SKU_ITEM."
                )
            }
        }

        // Validate quantity consistency for each item line
        for (item in request.itemsPicked) {
            val sourceTotal = item.sourceLocations.sumOf { it.quantityPicked }
            if (sourceTotal != item.totalQuantityPicked) {
                throw IllegalArgumentException(
                    "Quantity mismatch for ${item.quantityInventoryId}: " +
                    "totalQuantityPicked=${item.totalQuantityPicked}, " +
                    "sourceLocations sum=$sourceTotal"
                )
            }
        }

        // Validate package items match picked items
        val totalPickedPerQBI = request.itemsPicked.associate { it.quantityInventoryId to it.totalQuantityPicked }
        val totalPackagedPerQBI = mutableMapOf<String, Int>()

        for (pkg in request.packages) {
            for (item in pkg.packagedItems) {
                totalPackagedPerQBI[item.quantityInventoryId] =
                    (totalPackagedPerQBI[item.quantityInventoryId] ?: 0) + item.quantity
            }
        }

        for ((qbiId, pickedQty) in totalPickedPerQBI) {
            val packagedQty = totalPackagedPerQBI[qbiId] ?: 0
            if (pickedQty != packagedQty) {
                throw IllegalArgumentException(
                    "Package quantity mismatch for $qbiId: " +
                    "picked=$pickedQty, packaged=$packagedQty"
                )
            }
        }

        // Validate package barcodes are unique
        val packageBarcodes = request.packages.map { it.packageBarcode }
        if (packageBarcodes.size != packageBarcodes.distinct().size) {
            throw IllegalArgumentException("Duplicate package barcodes found")
        }

        // Validate AWB condition
        if (request.shippingDetails.awbCondition == AwbCondition.CREATE_FOR_CUSTOMER) {
            if (request.shippingDetails.carrier.isNullOrBlank()) {
                throw IllegalArgumentException("Carrier is required when awbCondition is CREATE_FOR_CUSTOMER")
            }
            if (request.shippingDetails.requestedServiceType == null) {
                throw IllegalArgumentException("Service type is required when awbCondition is CREATE_FOR_CUSTOMER")
            }
        }
    }

    /**
     * STEP 2: Check Inventory Availability at Locations
     * Validates that all locations have sufficient inventory
     */
    private fun checkInventoryAvailability(request: CreateLocationQuantityBasedRequest): Map<String, QuantityInventoryResponse> {
        val allQuantityInventoryIds = request.itemsPicked.map { it.quantityInventoryId }.distinct()

        // Batch fetch all quantity inventories
        val batchRequest = BatchGetQuantityInventoryRequest(allQuantityInventoryIds)
        val inventories = quantityInventoryClient.batchGetByIds(batchRequest).data
            ?: throw IllegalArgumentException("Failed to fetch quantity inventories")

        val inventoryMap = inventories.associateBy { it.quantityInventoryId }

        // Validate each item picked
        for (itemPicked in request.itemsPicked) {
            val inventory = inventoryMap[itemPicked.quantityInventoryId]
                ?: throw IllegalArgumentException("QuantityInventory not found: ${itemPicked.quantityInventoryId}")

            // Validate item type matches
            if (inventory.itemType != itemPicked.itemType) {
                throw IllegalArgumentException(
                    "Item type mismatch for ${itemPicked.quantityInventoryId}: " +
                    "expected=${itemPicked.itemType}, actual=${inventory.itemType}"
                )
            }

            // Validate does NOT have parent container (Scenario 3 is for bulk items only)
            if (inventory.parentContainerId != null || inventory.parentContainerBarcode != null) {
                throw IllegalArgumentException(
                    "QuantityInventory ${itemPicked.quantityInventoryId} has parent container ${inventory.parentContainerBarcode}. " +
                    "Must use container-based API instead."
                )
            }

            // Validate each source location
            val locationMap = inventory.locationAllocations.associateBy { it.locationCode }

            for (source in itemPicked.sourceLocations) {
                val locationAllocation = locationMap[source.locationCode]
                    ?: throw IllegalArgumentException(
                        "Location ${source.locationCode} not found in ${itemPicked.quantityInventoryId}. " +
                        "Available locations: ${locationMap.keys.joinToString()}"
                    )

                // Validate sufficient quantity at location
                if (locationAllocation.quantity < source.quantityPicked) {
                    throw IllegalArgumentException(
                        "Insufficient inventory at location ${source.locationCode}: " +
                        "available=${locationAllocation.quantity}, " +
                        "requested=${source.quantityPicked}, " +
                        "shortfall=${source.quantityPicked - locationAllocation.quantity}"
                    )
                }
            }
        }

        return inventoryMap
    }

    /**
     * STEP 3: Reduce Inventory with Location Updates
     * Calls Inventory Service to reduce quantities and update location allocations
     */
    private fun reduceInventoryWithLocations(
        request: CreateLocationQuantityBasedRequest,
        inventoryMap: Map<String, QuantityInventoryResponse>,
        user: String
    ): List<InventoryReductionData> {
        val reductions = mutableListOf<InventoryReductionData>()

        for (itemPicked in request.itemsPicked) {
            val beforeInventory = inventoryMap[itemPicked.quantityInventoryId]
                ?: throw IllegalStateException("Inventory not found: ${itemPicked.quantityInventoryId}")

            // Build location reductions
            val locationReductions = itemPicked.sourceLocations.map { source ->
                LocationReductionDto(
                    locationCode = source.locationCode,
                    quantityToShip = source.quantityPicked
                )
            }

            // Call Inventory Service to reduce quantity with location updates
            val reduceRequest = ReduceQuantityWithLocationsRequest(
                quantityInventoryId = itemPicked.quantityInventoryId,
                locationReductions = locationReductions,
                triggeredBy = user
            )

            val response = quantityInventoryClient.reduceQuantityWithLocationUpdate(reduceRequest).data
                ?: throw IllegalStateException("Failed to reduce inventory for ${itemPicked.quantityInventoryId}")

            val afterInventory = response.quantityInventory

            // Build location reduction summaries
            val locationReductionSummaries = itemPicked.sourceLocations.map { source ->
                val beforeLoc = beforeInventory.locationAllocations.find { it.locationCode == source.locationCode }
                val afterLoc = response.updatedLocations.find { it.locationCode == source.locationCode }

                LocationReductionSummaryData(
                    locationCode = source.locationCode,
                    quantityReduced = source.quantityPicked,
                    previousQuantity = beforeLoc?.quantity ?: 0,
                    newQuantity = afterLoc?.quantity ?: 0
                )
            }

            reductions.add(
                InventoryReductionData(
                    quantityInventoryId = itemPicked.quantityInventoryId,
                    itemType = itemPicked.itemType,
                    quantityReduced = itemPicked.totalQuantityPicked,
                    beforeQuantity = beforeInventory.availableQuantity,
                    afterQuantity = afterInventory.availableQuantity,
                    beforeLocations = beforeInventory.locationAllocations,
                    afterLocations = response.updatedLocations,
                    locationReductions = locationReductionSummaries
                )
            )
        }

        return reductions
    }

    /**
     * STEP 4: Consume Package Barcodes
     * Marks package barcodes as CONSUMED
     */
    private fun consumePackageBarcodes(packageBarcodes: List<String>) {
        try {
            val batchRequest = BatchConsumePackageBarcodesRequest(packageBarcodes)
            barcodeReservationClient.batchConsumePackageBarcodes(batchRequest)
        } catch (e: Exception) {
            logger.error("Failed to consume package barcodes", e)
            throw IllegalStateException("Failed to consume package barcodes: ${e.message}", e)
        }
    }

    /**
     * STEP 6: Create Quantity Transactions with Location Changes
     * Creates audit trail transactions with before/after location allocations
     */
    private fun createQuantityTransactionsWithLocations(
        request: CreateLocationQuantityBasedRequest,
        reductions: List<InventoryReductionData>,
        fulfillmentId: String,
        user: String
    ): List<String> {
        val transactionIds = mutableListOf<String>()

        for (reduction in reductions) {
            try {
                val beforeLocations = reduction.beforeLocations.map { loc ->
                    LocationAllocationDto(
                        locationCode = loc.locationCode,
                        quantity = loc.quantity,
                        allocatedAt = loc.allocatedAt
                    )
                }

                val afterLocations = reduction.afterLocations.map { loc ->
                    LocationAllocationDto(
                        locationCode = loc.locationCode,
                        quantity = loc.quantity,
                        allocatedAt = loc.allocatedAt
                    )
                }

                val txnRequest = CreateShipmentTransactionWithLocationsRequest(
                    quantityInventoryId = reduction.quantityInventoryId,
                    beforeQuantity = reduction.beforeQuantity,
                    afterQuantity = reduction.afterQuantity,
                    beforeLocations = beforeLocations,
                    afterLocations = afterLocations,
                    fulfillmentId = fulfillmentId,
                    user = user
                )

                val transaction = quantityTransactionClient.createShipmentTransactionWithLocations(txnRequest).data
                    ?: throw IllegalStateException("Failed to create transaction for ${reduction.quantityInventoryId}")

                transactionIds.add(transaction.transactionId)
            } catch (e: Exception) {
                logger.error("Failed to create quantity transaction for ${reduction.quantityInventoryId}", e)
                // Continue creating other transactions
            }
        }

        return transactionIds
    }

    /**
     * STEP 7: Build OrderFulfillmentRequest
     * Maps request data to OrderFulfillmentRequest model
     */
    private fun buildOrderFulfillmentRequest(
        request: CreateLocationQuantityBasedRequest,
        fulfillmentId: String,
        ginNumber: String,
        reductions: List<InventoryReductionData>,
        transactionIds: List<String>,
        user: String
    ): OrderFulfillmentRequest {
        val now = LocalDateTime.now()

        // Build line items
        val lineItems = request.itemsPicked.mapIndexed { index, itemPicked ->
            val lineItemId = sequenceGeneratorService.generateLineItemId()

            val reduction = reductions.find { it.quantityInventoryId == itemPicked.quantityInventoryId }
            val transactionId = transactionIds.getOrNull(reductions.indexOf(reduction))

            val qbiReference = QuantityInventoryReference(
                quantityInventoryId = itemPicked.quantityInventoryId,
                quantityShipped = itemPicked.totalQuantityPicked,
                containerBarcode = null,
                locationCode = null,  // Multiple locations, stored in quantitySourceDetails
                transactionId = transactionId ?: ""
            )

            // Parse itemType string to ItemType enum
            val parsedItemType = when (itemPicked.itemType.uppercase()) {
                "PALLET" -> ItemType.PALLET
                "BOX" -> ItemType.BOX
                else -> ItemType.BOX  // Default
            }

            LineItem(
                lineItemId = lineItemId,
                itemType = parsedItemType,
                skuId = null,  // No SKU for bulk items
                itemBarcode = null,
                allocationMethod = null,
                quantityOrdered = itemPicked.totalQuantityPicked,
                quantityPicked = itemPicked.totalQuantityPicked,
                quantityShipped = itemPicked.totalQuantityPicked,
                allocatedItems = mutableListOf(),
                quantityInventoryReferences = mutableListOf(qbiReference)
            )
        }.toMutableList()

        // Build packages
        val packages = request.packages.map { pkg ->
            // Build assigned items for this package
            val assignedItems = pkg.packagedItems.map { packagedItem ->
                // Parse itemType string to ItemType enum
                val parsedItemType = when (packagedItem.itemType.uppercase()) {
                    "PALLET" -> ItemType.PALLET
                    "BOX" -> ItemType.BOX
                    else -> ItemType.BOX
                }

                AssignedItem(
                    storageItemId = 0L,  // Placeholder for quantity-based (no individual storage items)
                    skuId = null,  // No SKU for bulk items
                    itemType = parsedItemType,
                    itemBarcode = packagedItem.quantityInventoryId  // Using QBI ID as reference
                )
            }.toMutableList()

            Package(
                packageId = sequenceGeneratorService.generatePackageId(),
                packageBarcode = pkg.packageBarcode,
                dimensions = PackageDimensions(
                    length = pkg.dimensions.length,
                    width = pkg.dimensions.width,
                    height = pkg.dimensions.height,
                    unit = pkg.dimensions.unit
                ),
                weight = PackageWeight(
                    value = pkg.weight.value,
                    unit = pkg.weight.unit
                ),
                assignedItems = assignedItems,
                createdAt = now
            )
        }.toMutableList()

        // Build quantity source details
        val quantitySourceDetails = QuantitySourceDetails(
            itemSources = request.itemsPicked.map { itemPicked ->
                // Parse itemType string to ItemType enum
                val parsedItemTypeForSource = when (itemPicked.itemType.uppercase()) {
                    "PALLET" -> ItemType.PALLET
                    "BOX" -> ItemType.BOX
                    else -> ItemType.BOX
                }

                ItemSource(
                    skuId = null,
                    totalQuantityPicked = itemPicked.totalQuantityPicked,
                    quantityInventoryId = itemPicked.quantityInventoryId,
                    itemType = parsedItemTypeForSource,
                    description = itemPicked.description,
                    containerSources = null,
                    locationSources = itemPicked.sourceLocations.map { source ->
                        LocationSource(
                            locationCode = source.locationCode,
                            quantityPicked = source.quantityPicked
                        )
                    }.toMutableList()
                )
            }.toMutableList()
        )

        return OrderFulfillmentRequest(
            fulfillmentId = fulfillmentId,
            accountId = request.accountId,
            fulfillmentType = FulfillmentType.LOCATION_QUANTITY_BASED,
            fulfillmentSource = FulfillmentSource.WEB_PORTAL,
            executionApproach = ExecutionApproach.DIRECT_PROCESSING,
            fulfillmentStatus = FulfillmentStatus.READY_TO_SHIP,
            priority = Priority.STANDARD,
            lineItems = lineItems,
            packages = packages,
            customerInfo = CustomerInfo(
                name = request.customerInfo.name,
                email = request.customerInfo.email,
                phone = request.customerInfo.phone
            ),
            shippingAddress = ShippingAddress(
                name = request.shippingAddress.name,
                company = request.shippingAddress.company,
                addressLine1 = request.shippingAddress.addressLine1,
                addressLine2 = request.shippingAddress.addressLine2,
                city = request.shippingAddress.city,
                state = request.shippingAddress.state,
                country = request.shippingAddress.country,
                postalCode = request.shippingAddress.postalCode,
                phone = request.shippingAddress.phone
            ),
            shippingDetails = ShippingDetails(
                awbCondition = request.shippingDetails.awbCondition ?: AwbCondition.CUSTOMER_CREATES,
                carrier = request.shippingDetails.carrier,
                requestedServiceType = request.shippingDetails.requestedServiceType
            ),
            ginNumber = ginNumber,
            quantitySourceDetails = quantitySourceDetails,
            notes = request.notes,
            tags = request.tags,
            customFields = request.customFields,
            pickingTaskId = null,
            packMoveTaskId = null,
            pickPackMoveTaskId = null,
            createdBy = user,
            createdAt = now,
            updatedAt = now,
            statusHistory = mutableListOf(
                StatusHistory(
                    status = FulfillmentStatus.READY_TO_SHIP,
                    timestamp = now,
                    user = user,
                    notes = "Location-based quantity picking completed"
                )
            )
        )
    }

    /**
     * STEP 10: Build Response
     * Constructs the response DTO
     */
    private fun buildResponse(
        ofr: OrderFulfillmentRequest,
        reductions: List<InventoryReductionData>,
        transactionIds: List<String>,
        awbNumber: String?,
        awbPdf: String?,
        trackingUrl: String?
    ): LocationQuantityBasedOFRResponse {
        val inventoryReductionsSummary = reductions.map { reduction ->
            InventoryReductionSummary(
                quantityInventoryId = reduction.quantityInventoryId,
                containerBarcode = null,
                skuId = null,
                itemType = reduction.itemType,
                quantityReduced = reduction.quantityReduced,
                previousAvailable = reduction.beforeQuantity,
                newAvailable = reduction.afterQuantity,
                locationReductions = reduction.locationReductions.map { locReduction ->
                    LocationReductionSummary(
                        locationCode = locReduction.locationCode,
                        quantityReduced = locReduction.quantityReduced,
                        previousQuantity = locReduction.previousQuantity,
                        newQuantity = locReduction.newQuantity
                    )
                }
            )
        }

        val totalLocationsUsed = reductions.flatMap { it.locationReductions }.distinctBy { it.locationCode }.size

        val summary = LocationQuantityOFRSummary(
            totalItemLines = ofr.lineItems.size,
            totalUnits = ofr.lineItems.sumOf { it.quantityOrdered },
            totalPackages = ofr.packages.size,
            totalLocationsUsed = totalLocationsUsed,
            inventoryReductionsSummary = inventoryReductionsSummary
        )

        return LocationQuantityBasedOFRResponse(
            fulfillmentId = ofr.fulfillmentId,
            ginNumber = ofr.ginNumber ?: "",
            fulfillmentStatus = ofr.fulfillmentStatus.name,
            summary = summary,
            awbGenerated = awbNumber != null,
            awbNumber = awbNumber,
            awbPdf = awbPdf,
            trackingUrl = trackingUrl,
            transactionsCreated = transactionIds,
            createdAt = ofr.createdAt.format(dateFormatter),
            updatedAt = ofr.updatedAt.format(dateFormatter)
        )
    }

    /**
     * Internal data classes to track inventory reductions
     */
    private data class InventoryReductionData(
        val quantityInventoryId: String,
        val itemType: String,
        val quantityReduced: Int,
        val beforeQuantity: Int,
        val afterQuantity: Int,
        val beforeLocations: List<com.wmspro.order.client.LocationAllocationResponseDto>,
        val afterLocations: List<com.wmspro.order.client.LocationAllocationResponseDto>,
        val locationReductions: List<LocationReductionSummaryData>
    )

    private data class LocationReductionSummaryData(
        val locationCode: String,
        val quantityReduced: Int,
        val previousQuantity: Int,
        val newQuantity: Int
    )
}
