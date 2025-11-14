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
 * Service for handling container-based quantity outbound fulfillment (Scenario 2)
 *
 * This service implements post-facto recording where:
 * - Items have already been physically picked from barcoded containers
 * - Items have already been packed into shipping packages
 * - System records what was done and reduces inventory
 */
@Service
@Transactional
class ContainerQuantityOutboundService(
    private val orderFulfillmentRequestRepository: OrderFulfillmentRequestRepository,
    private val inventoryServiceClient: InventoryServiceClient,
    private val productServiceClient: ProductServiceClient,
    private val sequenceGeneratorService: SequenceGeneratorService,
    private val orderFulfillmentService: OrderFulfillmentService
) {
    private val logger = LoggerFactory.getLogger(ContainerQuantityOutboundService::class.java)
    private val dateFormatter = DateTimeFormatter.ISO_DATE_TIME

    /**
     * Create OrderFulfillmentRequest with container-based quantity picking
     *
     * Implements 9-step workflow from backend implementation guide:
     * 1. Validate Request
     * 2. Check Inventory Availability
     * 3. Reduce Inventory
     * 4. Consume Package Barcodes
     * 5. Generate IDs
     * 6. Build OrderFulfillmentRequest
     * 7. Generate AWB (If Requested)
     * 8. Save OrderFulfillmentRequest
     * 9. Build Response
     */
    fun createOFR(request: CreateContainerQuantityBasedRequest, user: String): ContainerQuantityBasedOFRResponse {
        val tenantId = TenantContext.requireCurrentTenant()

        logger.info("Creating container-based quantity OFR for accountId=${request.accountId}, tenant=$tenantId")

        // STEP 1: Validate Request
        logger.debug("Step 1: Validating request")
        validateRequest(request)

        // STEP 2: Check Inventory Availability
        logger.debug("Step 2: Checking inventory availability")
        val inventoryData = checkInventoryAvailability(request)

        // STEP 3: Reduce Inventory
        logger.debug("Step 3: Reducing inventory")
        val inventoryReductions = reduceInventory(request, inventoryData, user)

        // STEP 4: Consume Package Barcodes
        logger.debug("Step 4: Consuming package barcodes")
        consumePackageBarcodes(request.packages.map { it.packageBarcode })

        // STEP 5: Generate IDs
        logger.debug("Step 5: Generating IDs")
        val fulfillmentId = sequenceGeneratorService.generateOfrId()
        val ginNumber = sequenceGeneratorService.generateGinNumber()

        // STEP 6: Create Quantity Transactions
        logger.debug("Step 6: Creating quantity transactions")
        val transactionIds = createQuantityTransactions(request, inventoryReductions, fulfillmentId, user)

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

        logger.info("Successfully created container-based quantity OFR: $fulfillmentId, GIN: $ginNumber")

        // STEP 10: Build Response
        return buildResponse(savedOFR, inventoryReductions, transactionIds, awbNumber, awbPdf, trackingUrl)
    }

    /**
     * STEP 1: Validate Request
     * Validates all aspects of the request
     */
    private fun validateRequest(request: CreateContainerQuantityBasedRequest) {
        // Validate quantity consistency for each item line
        for (item in request.itemsPicked) {
            val sourceTotal = item.sourceContainers.sumOf { it.quantityPicked }
            if (sourceTotal != item.totalQuantityPicked) {
                throw IllegalArgumentException(
                    "Quantity mismatch for SKU ${item.skuId}: " +
                    "totalQuantityPicked=${item.totalQuantityPicked}, " +
                    "sourceContainers sum=$sourceTotal"
                )
            }
        }

        // Validate package items match picked items
        val totalPickedPerSku = request.itemsPicked.associate { it.skuId to it.totalQuantityPicked }
        val totalPackagedPerSku = mutableMapOf<Long, Int>()

        for (pkg in request.packages) {
            for (item in pkg.packagedItems) {
                totalPackagedPerSku[item.skuId] = (totalPackagedPerSku[item.skuId] ?: 0) + item.quantity
            }
        }

        for ((skuId, pickedQty) in totalPickedPerSku) {
            val packagedQty = totalPackagedPerSku[skuId] ?: 0
            if (pickedQty != packagedQty) {
                throw IllegalArgumentException(
                    "Package quantity mismatch for SKU $skuId: " +
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
     * STEP 2: Check Inventory Availability
     * Validates that all containers have sufficient inventory
     */
    private fun checkInventoryAvailability(request: CreateContainerQuantityBasedRequest): Map<String, QuantityInventoryResponse> {
        val allQuantityInventoryIds = request.itemsPicked
            .flatMap { it.sourceContainers }
            .map { it.quantityInventoryId }
            .distinct()

        // Batch fetch all quantity inventories
        val batchRequest = BatchGetQuantityInventoryRequest(allQuantityInventoryIds)
        val inventories = inventoryServiceClient.batchGetByIds(batchRequest).data
            ?: throw IllegalArgumentException("Failed to fetch quantity inventories")

        val inventoryMap = inventories.associateBy { it.quantityInventoryId }

        // Validate each source container
        for (itemPicked in request.itemsPicked) {
            for (source in itemPicked.sourceContainers) {
                val inventory = inventoryMap[source.quantityInventoryId]
                    ?: throw IllegalArgumentException("QuantityInventory not found: ${source.quantityInventoryId}")

                // Validate container barcode matches
                if (inventory.parentContainerBarcode != source.containerBarcode) {
                    throw IllegalArgumentException(
                        "Container barcode mismatch for ${source.quantityInventoryId}: " +
                        "expected=${source.containerBarcode}, actual=${inventory.parentContainerBarcode}"
                    )
                }

                // Validate SKU matches
                if (inventory.skuId != itemPicked.skuId) {
                    throw IllegalArgumentException(
                        "SKU mismatch for ${source.quantityInventoryId}: " +
                        "expected=${itemPicked.skuId}, actual=${inventory.skuId}"
                    )
                }

                // Validate sufficient quantity available
                if (inventory.availableQuantity < source.quantityPicked) {
                    throw IllegalArgumentException(
                        "Insufficient inventory in ${source.containerBarcode}: " +
                        "available=${inventory.availableQuantity}, " +
                        "requested=${source.quantityPicked}, " +
                        "shortfall=${source.quantityPicked - inventory.availableQuantity}"
                    )
                }
            }
        }

        return inventoryMap
    }

    /**
     * STEP 3: Reduce Inventory
     * Calls Inventory Service to reduce quantities
     */
    private fun reduceInventory(
        request: CreateContainerQuantityBasedRequest,
        inventoryMap: Map<String, QuantityInventoryResponse>,
        user: String
    ): List<InventoryReductionData> {
        val reductions = mutableListOf<InventoryReductionData>()

        for (itemPicked in request.itemsPicked) {
            for (source in itemPicked.sourceContainers) {
                val beforeInventory = inventoryMap[source.quantityInventoryId]
                    ?: throw IllegalStateException("Inventory not found: ${source.quantityInventoryId}")

                // Call Inventory Service to reduce quantity
                val reduceRequest = ReduceQuantityRequest(
                    quantityInventoryId = source.quantityInventoryId,
                    quantityToShip = source.quantityPicked,
                    triggeredBy = user
                )

                val afterInventory = inventoryServiceClient.reduceQuantityForShipment(reduceRequest).data
                    ?: throw IllegalStateException("Failed to reduce inventory for ${source.quantityInventoryId}")

                reductions.add(
                    InventoryReductionData(
                        quantityInventoryId = source.quantityInventoryId,
                        containerBarcode = source.containerBarcode,
                        skuId = itemPicked.skuId,
                        quantityReduced = source.quantityPicked,
                        beforeQuantity = beforeInventory.availableQuantity,
                        afterQuantity = afterInventory.availableQuantity,
                        locationCode = source.locationCode
                    )
                )
            }
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
            inventoryServiceClient.batchConsumePackageBarcodes(batchRequest)
        } catch (e: Exception) {
            logger.error("Failed to consume package barcodes", e)
            throw IllegalStateException("Failed to consume package barcodes: ${e.message}", e)
        }
    }

    /**
     * STEP 6: Create Quantity Transactions
     * Creates audit trail transactions for each inventory reduction
     */
    private fun createQuantityTransactions(
        request: CreateContainerQuantityBasedRequest,
        reductions: List<InventoryReductionData>,
        fulfillmentId: String,
        user: String
    ): List<String> {
        val transactionIds = mutableListOf<String>()

        for (reduction in reductions) {
            try {
                val txnRequest = CreateShipmentTransactionRequest(
                    quantityInventoryId = reduction.quantityInventoryId,
                    beforeQuantity = reduction.beforeQuantity,
                    afterQuantity = reduction.afterQuantity,
                    fulfillmentId = fulfillmentId,
                    user = user
                )

                val transaction = inventoryServiceClient.createShipmentTransaction(txnRequest).data
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
        request: CreateContainerQuantityBasedRequest,
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

            // Get quantity inventory references for this SKU
            val qbiReferences = reductions
                .filter { it.skuId == itemPicked.skuId }
                .map { reduction ->
                    QuantityInventoryReference(
                        quantityInventoryId = reduction.quantityInventoryId,
                        quantityShipped = reduction.quantityReduced,
                        containerBarcode = reduction.containerBarcode,
                        locationCode = reduction.locationCode,
                        transactionId = transactionIds.getOrNull(reductions.indexOf(reduction)) ?: ""
                    )
                }

            LineItem(
                lineItemId = lineItemId,
                itemType = ItemType.SKU_ITEM,
                skuId = itemPicked.skuId,
                itemBarcode = null,
                allocationMethod = null,
                quantityOrdered = itemPicked.totalQuantityPicked,
                quantityPicked = itemPicked.totalQuantityPicked,
                quantityShipped = itemPicked.totalQuantityPicked,
                allocatedItems = mutableListOf(),  // Empty for Scenario 2
                quantityInventoryReferences = qbiReferences.toMutableList()
            )
        }.toMutableList()

        // Build packages
        val packages = request.packages.map { pkg ->
            // Build assigned items for this package
            val assignedItems = pkg.packagedItems.flatMap { packagedItem ->
                // Find all reductions for this SKU
                val skuReductions = reductions.filter { it.skuId == packagedItem.skuId }

                // Create AssignedItem entries (using dummy storageItemId since we don't have individual items)
                skuReductions.map { reduction ->
                    AssignedItem(
                        storageItemId = 0L,  // Placeholder for quantity-based (no individual storage items)
                        skuId = packagedItem.skuId,
                        itemType = ItemType.SKU_ITEM,
                        itemBarcode = reduction.containerBarcode  // Using container barcode as reference
                    )
                }
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
                ItemSource(
                    skuId = itemPicked.skuId,
                    totalQuantityPicked = itemPicked.totalQuantityPicked,
                    quantityInventoryId = null,
                    itemType = null,
                    description = null,
                    containerSources = itemPicked.sourceContainers.map { source ->
                        ContainerSource(
                            containerBarcode = source.containerBarcode,
                            quantityInventoryId = source.quantityInventoryId,
                            quantityPicked = source.quantityPicked,
                            locationCode = source.locationCode
                        )
                    }.toMutableList(),
                    locationSources = null
                )
            }.toMutableList()
        )

        return OrderFulfillmentRequest(
            fulfillmentId = fulfillmentId,
            accountId = request.accountId,
            fulfillmentType = FulfillmentType.CONTAINER_QUANTITY_BASED,
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
                    notes = "Container-based quantity picking completed"
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
    ): ContainerQuantityBasedOFRResponse {
        val inventoryReductionsSummary = reductions.map { reduction ->
            InventoryReductionSummary(
                quantityInventoryId = reduction.quantityInventoryId,
                containerBarcode = reduction.containerBarcode,
                skuId = reduction.skuId,
                itemType = null,
                quantityReduced = reduction.quantityReduced,
                previousAvailable = reduction.beforeQuantity,
                newAvailable = reduction.afterQuantity,
                locationReductions = null
            )
        }

        val summary = ContainerQuantityOFRSummary(
            totalItemLines = ofr.lineItems.size,
            totalUnits = ofr.lineItems.sumOf { it.quantityOrdered },
            totalPackages = ofr.packages.size,
            totalContainerSourcesUsed = reductions.size,
            inventoryReductionsSummary = inventoryReductionsSummary
        )

        return ContainerQuantityBasedOFRResponse(
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
     * Internal data class to track inventory reductions
     */
    private data class InventoryReductionData(
        val quantityInventoryId: String,
        val containerBarcode: String,
        val skuId: Long,
        val quantityReduced: Int,
        val beforeQuantity: Int,
        val afterQuantity: Int,
        val locationCode: String
    )
}
