package com.wmspro.order.service

import com.wmspro.common.service.AccountService
import com.wmspro.common.service.UserService
import com.wmspro.order.client.InventoryServiceClient
import com.wmspro.order.client.ProductServiceClient
import com.wmspro.order.client.StorageItemBarcodesByIdsRequest
import com.wmspro.order.dto.*
import com.wmspro.order.enums.FulfillmentType
import com.wmspro.order.enums.ItemType
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Service for comprehensive OFR data enrichment
 * Fetches data from multiple microservices and enriches OFR response
 */
@Service
class OfrEnrichmentService(
    private val ofrRepository: OrderFulfillmentRequestRepository,
    private val productServiceClient: ProductServiceClient,
    private val inventoryServiceClient: InventoryServiceClient,
    private val accountService: AccountService,
    private val userService: UserService
) {

    private val logger = LoggerFactory.getLogger(OfrEnrichmentService::class.java)

    /**
     * Get comprehensive enriched OFR with all details from multiple microservices
     */
    fun getEnrichedOfrById(ofrId: String, authToken: String): OfrEnrichedResponse {
        logger.info("Getting enriched OFR for: $ofrId")

        // 1. Fetch OFR from database
        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        // 2. Collect all IDs that need enrichment
        val enrichmentData = collectIdsForEnrichment(ofr)

        logger.info("Enrichment needed - SKUs: ${enrichmentData.skuIds.size}, " +
                "StorageItems: ${enrichmentData.storageItemIds.size}, " +
                "QuantityInventories: ${enrichmentData.quantityInventoryIds.size}")

        // 3. Fetch enriched data from various services
        val skuDetailsMap = fetchSkuDetails(enrichmentData.skuIds)
        val storageItemDetailsMap = fetchStorageItemDetails(enrichmentData.storageItemIds)
        val quantityInventoryDetailsMap = fetchQuantityInventoryDetails(enrichmentData.quantityInventoryIds)
        val accountName = fetchAccountName(ofr.accountId, authToken)
        val userNames = fetchUserNames(enrichmentData.userIds, authToken)

        // 4. Build enriched response
        return buildEnrichedResponse(
            ofr = ofr,
            skuDetailsMap = skuDetailsMap,
            storageItemDetailsMap = storageItemDetailsMap,
            quantityInventoryDetailsMap = quantityInventoryDetailsMap,
            accountName = accountName,
            userNames = userNames
        )
    }

    /**
     * Data class to hold all IDs that need enrichment
     */
    private data class EnrichmentIds(
        val skuIds: Set<Long>,
        val storageItemIds: Set<Long>,
        val quantityInventoryIds: Set<String>,
        val userIds: Set<String>
    )

    /**
     * Collect all IDs from OFR that need enrichment
     */
    private fun collectIdsForEnrichment(ofr: OrderFulfillmentRequest): EnrichmentIds {
        val skuIds = mutableSetOf<Long>()
        val storageItemIds = mutableSetOf<Long>()
        val quantityInventoryIds = mutableSetOf<String>()
        val userIds = mutableSetOf<String>()

        // Collect user IDs
        ofr.createdBy?.let { userIds.add(it) }
        ofr.updatedBy?.let { userIds.add(it) }

        // Collect from line items
        ofr.lineItems.forEach { lineItem ->
            // SKU IDs
            lineItem.skuId?.let { skuIds.add(it) }

            // Storage Item IDs from allocated items
            lineItem.allocatedItems.forEach { allocatedItem ->
                storageItemIds.add(allocatedItem.storageItemId)
            }

            // Quantity Inventory IDs
            lineItem.quantityInventoryReferences.forEach { qiRef ->
                quantityInventoryIds.add(qiRef.quantityInventoryId)
            }
        }

        // Collect from packages
        ofr.packages.forEach { pkg ->
            pkg.assignedItems.forEach { assignedItem ->
                assignedItem.skuId?.let { skuIds.add(it) }
                storageItemIds.add(assignedItem.storageItemId)
            }
        }

        // Collect from quantity source details
        ofr.quantitySourceDetails?.itemSources?.forEach { itemSource ->
            itemSource.skuId?.let { skuIds.add(it) }
            itemSource.quantityInventoryId?.let { quantityInventoryIds.add(it) }

            itemSource.containerSources?.forEach { containerSource ->
                quantityInventoryIds.add(containerSource.quantityInventoryId)
            }
        }

        return EnrichmentIds(skuIds, storageItemIds, quantityInventoryIds, userIds)
    }

    /**
     * Fetch SKU details from Product Service
     */
    private data class SKUDetails(
        val skuCode: String?,
        val productTitle: String?,
        val primaryImageUrl: String?
    )

    private fun fetchSkuDetails(skuIds: Set<Long>): Map<Long, SKUDetails> {
        if (skuIds.isEmpty()) {
            logger.debug("No SKU IDs to fetch")
            return emptyMap()
        }

        logger.info("Fetching SKU details for ${skuIds.size} SKUs")

        return try {
            val request = BatchSkuRequest(
                skuIds = skuIds.toList(),
                fields = listOf("skuId", "skuCode", "productTitle", "images")
            )

            val response = productServiceClient.getBatchSkuDetails(request)

            if (!response.success || response.data == null) {
                logger.error("Product service returned error: ${response.message}")
                return createErrorSkuDetailsMap(skuIds)
            }

            val responseData = response.data!!
            @Suppress("UNCHECKED_CAST")
            val skuList = responseData["skus"] as? List<Map<String, Any>>
                ?: throw RuntimeException("Invalid response format from product service")

            val resultMap = mutableMapOf<Long, SKUDetails>()

            skuList.forEach { skuData ->
                val skuId = (skuData["skuId"] as? Number)?.toLong()
                if (skuId != null) {
                    val skuCode = skuData["skuCode"] as? String
                    val productTitle = skuData["productTitle"] as? String

                    val primaryImageUrl = try {
                        @Suppress("UNCHECKED_CAST")
                        val images = skuData["images"] as? List<Map<String, Any>>
                        images?.let { imageList ->
                            val primaryImage = imageList.firstOrNull { (it["isPrimary"] as? Boolean) == true }
                            val imageToUse = primaryImage ?: imageList.firstOrNull()
                            imageToUse?.get("url") as? String
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to extract image URL for SKU $skuId: ${e.message}")
                        null
                    }

                    resultMap[skuId] = SKUDetails(skuCode, productTitle, primaryImageUrl)
                }
            }

            // Fill missing SKUs with N/A
            skuIds.forEach { skuId ->
                if (!resultMap.containsKey(skuId)) {
                    logger.warn("SKU details not found for SKU ID: $skuId")
                    resultMap[skuId] = SKUDetails("N/A", "N/A", null)
                }
            }

            logger.info("Successfully fetched SKU details for ${resultMap.size} SKUs")
            resultMap

        } catch (e: Exception) {
            logger.error("Failed to fetch SKU details from product service", e)
            createErrorSkuDetailsMap(skuIds)
        }
    }

    private fun createErrorSkuDetailsMap(skuIds: Set<Long>): Map<Long, SKUDetails> {
        return skuIds.associateWith { SKUDetails("N/A (ERR)", "N/A (ERR)", null) }
    }

    /**
     * Fetch Storage Item details from Inventory Service
     */
    private fun fetchStorageItemDetails(storageItemIds: Set<Long>): Map<Long, com.wmspro.order.client.StorageItemBarcodeResponse> {
        if (storageItemIds.isEmpty()) {
            logger.debug("No Storage Item IDs to fetch")
            return emptyMap()
        }

        logger.info("Fetching Storage Item details for ${storageItemIds.size} items")

        return try {
            val request = StorageItemBarcodesByIdsRequest(storageItemIds = storageItemIds.toList())
            val response = inventoryServiceClient.getBarcodesByStorageItemIds(request)

            if (!response.success || response.data == null) {
                logger.error("Inventory service returned error: ${response.message}")
                return emptyMap()
            }

            val resultMap = response.data!!.associateBy { it.storageItemId }
            logger.info("Successfully fetched Storage Item details for ${resultMap.size} items")
            resultMap

        } catch (e: Exception) {
            logger.error("Failed to fetch Storage Item details from inventory service", e)
            emptyMap()
        }
    }

    /**
     * Fetch Quantity Inventory details from Inventory Service
     */
    private fun fetchQuantityInventoryDetails(quantityInventoryIds: Set<String>): Map<String, com.wmspro.order.client.QuantityInventoryResponse> {
        if (quantityInventoryIds.isEmpty()) {
            logger.debug("No Quantity Inventory IDs to fetch")
            return emptyMap()
        }

        logger.info("Fetching Quantity Inventory details for ${quantityInventoryIds.size} items")

        return try {
            val request = com.wmspro.order.client.BatchGetQuantityInventoryRequest(
                quantityInventoryIds = quantityInventoryIds.toList()
            )
            val response = inventoryServiceClient.batchGetByIds(request)

            if (!response.success || response.data == null) {
                logger.error("Inventory service returned error: ${response.message}")
                return emptyMap()
            }

            val resultMap = response.data!!.associateBy { it.quantityInventoryId }
            logger.info("Successfully fetched Quantity Inventory details for ${resultMap.size} items")
            resultMap

        } catch (e: Exception) {
            logger.error("Failed to fetch Quantity Inventory details from inventory service", e)
            emptyMap()
        }
    }

    /**
     * Fetch Account name from Account Service
     */
    private fun fetchAccountName(accountId: Long, authToken: String): String? {
        return try {
            if (authToken.isNotEmpty()) {
                accountService.fetchAccountNames(listOf(accountId), authToken)[accountId.toString()]
            } else {
                logger.warn("No auth token provided, skipping account name fetch")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch account name for account ID: $accountId", e)
            null
        }
    }

    /**
     * Fetch User names from User Service
     */
    private fun fetchUserNames(userIds: Set<String>, authToken: String): Map<String, String> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }

        return try {
            if (authToken.isNotEmpty()) {
                userService.fetchUserFullNames(userIds.toList(), authToken)
            } else {
                logger.warn("No auth token provided, skipping user names fetch")
                emptyMap()
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch user names", e)
            emptyMap()
        }
    }

    /**
     * Build enriched OFR response with all fetched data
     */
    private fun buildEnrichedResponse(
        ofr: OrderFulfillmentRequest,
        skuDetailsMap: Map<Long, SKUDetails>,
        storageItemDetailsMap: Map<Long, com.wmspro.order.client.StorageItemBarcodeResponse>,
        quantityInventoryDetailsMap: Map<String, com.wmspro.order.client.QuantityInventoryResponse>,
        accountName: String?,
        userNames: Map<String, String>
    ): OfrEnrichedResponse {
        logger.info("Building enriched response for OFR: ${ofr.fulfillmentId}")

        return OfrEnrichedResponse(
            fulfillmentId = ofr.fulfillmentId,
            accountId = ofr.accountId,
            accountName = accountName,
            fulfillmentSource = ofr.fulfillmentSource,
            fulfillmentType = ofr.fulfillmentType,
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
            lineItems = enrichLineItems(ofr.lineItems, skuDetailsMap, storageItemDetailsMap, quantityInventoryDetailsMap),
            packages = enrichPackages(ofr.packages, skuDetailsMap, storageItemDetailsMap),
            quantitySourceDetails = enrichQuantitySourceDetails(ofr.quantitySourceDetails, skuDetailsMap, quantityInventoryDetailsMap),
            shippingDetails = ofr.shippingDetails,
            statusHistory = ofr.statusHistory,
            fulfillmentRequestDate = ofr.fulfillmentRequestDate,
            orderValue = ofr.orderValue,
            ginNumber = ofr.ginNumber,
            ginNotification = ofr.ginNotification,
            loadingDocuments = ofr.loadingDocuments,
            notes = ofr.notes,
            tags = ofr.tags,
            customFields = ofr.customFields,
            cancelledAt = ofr.cancelledAt,
            cancellationReason = ofr.cancellationReason,
            createdBy = ofr.createdBy,
            createdByUserName = ofr.createdBy?.let { userNames[it] },
            updatedBy = ofr.updatedBy,
            updatedByUserName = ofr.updatedBy?.let { userNames[it] },
            createdAt = ofr.createdAt,
            updatedAt = ofr.updatedAt
        )
    }

    /**
     * Enrich line items with SKU and storage item details
     */
    private fun enrichLineItems(
        lineItems: List<com.wmspro.order.model.LineItem>,
        skuDetailsMap: Map<Long, SKUDetails>,
        storageItemDetailsMap: Map<Long, com.wmspro.order.client.StorageItemBarcodeResponse>,
        quantityInventoryDetailsMap: Map<String, com.wmspro.order.client.QuantityInventoryResponse>
    ): List<LineItemEnriched> {
        return lineItems.map { lineItem ->
            val skuDetails = lineItem.skuId?.let { skuDetailsMap[it] }

            LineItemEnriched(
                lineItemId = lineItem.lineItemId,
                itemType = lineItem.itemType,
                skuId = lineItem.skuId,
                skuCode = skuDetails?.skuCode,
                productTitle = skuDetails?.productTitle,
                productImageUrl = skuDetails?.primaryImageUrl,
                itemBarcode = lineItem.itemBarcode,
                allocationMethod = lineItem.allocationMethod,
                quantityOrdered = lineItem.quantityOrdered,
                quantityPicked = lineItem.quantityPicked,
                quantityShipped = lineItem.quantityShipped,
                allocatedItems = enrichAllocatedItems(lineItem.allocatedItems, storageItemDetailsMap),
                quantityInventoryReferences = enrichQuantityInventoryReferences(lineItem.quantityInventoryReferences, quantityInventoryDetailsMap)
            )
        }
    }

    /**
     * Enrich allocated items with storage item details
     */
    private fun enrichAllocatedItems(
        allocatedItems: List<com.wmspro.order.model.AllocatedItem>,
        storageItemDetailsMap: Map<Long, com.wmspro.order.client.StorageItemBarcodeResponse>
    ): List<AllocatedItemEnriched> {
        return allocatedItems.map { allocatedItem ->
            val storageItemDetails = storageItemDetailsMap[allocatedItem.storageItemId]

            AllocatedItemEnriched(
                storageItemId = allocatedItem.storageItemId,
                location = allocatedItem.location,
                itemBarcode = storageItemDetails?.itemBarcode,
                itemType = storageItemDetails?.itemType,
                skuId = storageItemDetails?.skuId,
                lengthCm = storageItemDetails?.lengthCm,
                widthCm = storageItemDetails?.widthCm,
                heightCm = storageItemDetails?.heightCm,
                volumeCbm = storageItemDetails?.volumeCbm,
                weightKg = storageItemDetails?.weightKg,
                parentItemBarcode = storageItemDetails?.parentItemBarcode,
                picked = allocatedItem.picked,
                pickedAt = allocatedItem.pickedAt,
                pickedBy = allocatedItem.pickedBy
            )
        }
    }

    /**
     * Enrich quantity inventory references
     */
    private fun enrichQuantityInventoryReferences(
        quantityInventoryReferences: List<com.wmspro.order.model.QuantityInventoryReference>,
        quantityInventoryDetailsMap: Map<String, com.wmspro.order.client.QuantityInventoryResponse>
    ): List<QuantityInventoryReferenceEnriched> {
        return quantityInventoryReferences.map { qiRef ->
            val qiDetails = quantityInventoryDetailsMap[qiRef.quantityInventoryId]

            QuantityInventoryReferenceEnriched(
                quantityInventoryId = qiRef.quantityInventoryId,
                quantityShipped = qiRef.quantityShipped,
                containerBarcode = qiRef.containerBarcode,
                locationCode = qiRef.locationCode,
                transactionId = qiRef.transactionId,
                itemType = qiDetails?.itemType,
                totalQuantity = qiDetails?.totalQuantity,
                availableQuantity = qiDetails?.availableQuantity,
                description = qiDetails?.description
            )
        }
    }

    /**
     * Enrich packages with SKU and storage item details
     */
    private fun enrichPackages(
        packages: List<com.wmspro.order.model.Package>,
        skuDetailsMap: Map<Long, SKUDetails>,
        storageItemDetailsMap: Map<Long, com.wmspro.order.client.StorageItemBarcodeResponse>
    ): List<PackageEnriched> {
        return packages.map { pkg ->
            PackageEnriched(
                packageId = pkg.packageId,
                packageBarcode = pkg.packageBarcode,
                dimensions = pkg.dimensions,
                weight = pkg.weight,
                assignedItems = enrichAssignedItems(pkg.assignedItems, skuDetailsMap, storageItemDetailsMap),
                dispatchArea = pkg.dispatchArea,
                dispatchAreaBarcode = pkg.dispatchAreaBarcode,
                droppedAtDispatch = pkg.droppedAtDispatch,
                dispatchScannedAt = pkg.dispatchScannedAt,
                createdAt = pkg.createdAt,
                createdByTask = pkg.createdByTask,
                savedAt = pkg.savedAt,
                loadedOnTruck = pkg.loadedOnTruck,
                truckNumber = pkg.truckNumber,
                loadedAt = pkg.loadedAt
            )
        }
    }

    /**
     * Enrich assigned items with SKU and storage item details
     */
    private fun enrichAssignedItems(
        assignedItems: List<com.wmspro.order.model.AssignedItem>,
        skuDetailsMap: Map<Long, SKUDetails>,
        storageItemDetailsMap: Map<Long, com.wmspro.order.client.StorageItemBarcodeResponse>
    ): List<AssignedItemEnriched> {
        return assignedItems.map { assignedItem ->
            val skuDetails = assignedItem.skuId?.let { skuDetailsMap[it] }
            val storageItemDetails = storageItemDetailsMap[assignedItem.storageItemId]

            AssignedItemEnriched(
                storageItemId = assignedItem.storageItemId,
                skuId = assignedItem.skuId,
                itemType = assignedItem.itemType,
                itemBarcode = assignedItem.itemBarcode,
                skuCode = skuDetails?.skuCode,
                productTitle = skuDetails?.productTitle,
                productImageUrl = skuDetails?.primaryImageUrl,
                lengthCm = storageItemDetails?.lengthCm,
                widthCm = storageItemDetails?.widthCm,
                heightCm = storageItemDetails?.heightCm,
                volumeCbm = storageItemDetails?.volumeCbm,
                weightKg = storageItemDetails?.weightKg
            )
        }
    }

    /**
     * Enrich quantity source details
     */
    private fun enrichQuantitySourceDetails(
        quantitySourceDetails: com.wmspro.order.model.QuantitySourceDetails?,
        skuDetailsMap: Map<Long, SKUDetails>,
        quantityInventoryDetailsMap: Map<String, com.wmspro.order.client.QuantityInventoryResponse>
    ): QuantitySourceDetailsEnriched? {
        if (quantitySourceDetails == null) return null

        return QuantitySourceDetailsEnriched(
            itemSources = quantitySourceDetails.itemSources.map { itemSource ->
                val skuDetails = itemSource.skuId?.let { skuDetailsMap[it] }

                ItemSourceEnriched(
                    skuId = itemSource.skuId,
                    skuCode = skuDetails?.skuCode,
                    productTitle = skuDetails?.productTitle,
                    totalQuantityPicked = itemSource.totalQuantityPicked,
                    quantityInventoryId = itemSource.quantityInventoryId,
                    itemType = itemSource.itemType,
                    description = itemSource.description,
                    containerSources = itemSource.containerSources?.map { containerSource ->
                        val qiDetails = quantityInventoryDetailsMap[containerSource.quantityInventoryId]

                        ContainerSourceEnriched(
                            containerBarcode = containerSource.containerBarcode,
                            quantityInventoryId = containerSource.quantityInventoryId,
                            quantityPicked = containerSource.quantityPicked,
                            locationCode = containerSource.locationCode,
                            itemType = qiDetails?.itemType,
                            totalQuantity = qiDetails?.totalQuantity,
                            availableQuantity = qiDetails?.availableQuantity,
                            description = qiDetails?.description
                        )
                    },
                    locationSources = itemSource.locationSources
                )
            }
        )
    }
}
