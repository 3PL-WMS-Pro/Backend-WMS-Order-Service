package com.wmspro.order.service

import com.wmspro.common.service.AccountService
import com.wmspro.common.tenant.TenantContext
import com.wmspro.order.client.*
import com.wmspro.order.dto.*
import com.wmspro.order.enums.ItemType
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Service for aggregating data needed for GIN generation
 */
@Service
class GinDataAggregationService(
    private val ofrRepository: OrderFulfillmentRequestRepository,
    private val tenantServiceClient: TenantServiceClient,
    private val accountService: AccountService,
    private val inventoryServiceClient: InventoryServiceClient,
    private val productServiceClient: ProductServiceClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Aggregate all data needed for GIN generation
     */
    fun aggregateGinData(
        fulfillmentId: String,
        authToken: String
    ): GinDataDto {
        logger.info("Aggregating GIN data for fulfillment request: {}", fulfillmentId)

        // 1. Fetch Order Fulfillment Request
        val ofr = ofrRepository.findById(fulfillmentId).orElse(null)
            ?: throw IllegalArgumentException("Order Fulfillment Request not found: $fulfillmentId")

        // 2. Get tenant info
        val tenantId = TenantContext.getCurrentTenant() ?: throw IllegalStateException("Tenant context not available")
        val tenantResponse = try {
            tenantServiceClient.getTenantByClientId(tenantId.toInt(), false)
        } catch (e: Exception) {
            logger.warn("Failed to fetch tenant info, using default", e)
            null
        }
        val tenantName = tenantResponse?.data?.tenantName ?: "Warehouse"

        // 3. Get account/customer name
        val accountName = try {
            val accountNames = accountService.fetchAccountNames(listOf(ofr.accountId), authToken)
            accountNames[ofr.accountId.toString()] ?: "Customer ${ofr.accountId}"
        } catch (e: Exception) {
            logger.warn("Failed to fetch account name", e)
            "Customer ${ofr.accountId}"
        }

        // 4. Fetch dimensions for all allocated items (STANDARD_TASK_BASED)
        val allStorageItemIds = ofr.lineItems.flatMap { lineItem ->
            lineItem.allocatedItems.map { it.storageItemId }
        }
        val itemDimensionsByStorageId = fetchItemDimensions(allStorageItemIds)

        // 4b. Fetch QBI details for quantity-based fulfillment (LOCATION/CONTAINER_QUANTITY_BASED)
        val allQbiIds = ofr.lineItems.flatMap { lineItem ->
            lineItem.quantityInventoryReferences.map { it.quantityInventoryId }
        }.distinct()
        val qbiDetailsMap = if (allQbiIds.isNotEmpty()) fetchQbiDetails(allQbiIds) else emptyMap()

        // 5. Fetch SKU details for all SKU items in batch
        val allSkuIds = ofr.lineItems
            .filter { it.itemType == ItemType.SKU_ITEM && it.skuId != null }
            .mapNotNull { it.skuId }
            .distinct()
        val skuDetailsMap = fetchSkuDetailsInBatch(allSkuIds)

        // 6. Process line items for GIN
        val ginItems = processLineItemsForGin(ofr, skuDetailsMap, itemDimensionsByStorageId, qbiDetailsMap)

        // 7. Calculate totals
        val totalOrdered = ginItems.sumOf { it.quantityOrdered }
        val totalPicked = ginItems.sumOf { it.quantityPicked }
        val totalShipped = ginItems.sumOf { it.quantityShipped }
        val totalCBM = ginItems.sumOf { item -> (item.cbm ?: 0.0) * item.quantityShipped }
        val totalWeight = ginItems.sumOf { item -> (item.weight ?: 0.0) * item.quantityShipped }

        // 8. Determine current status
        val currentStatus = ofr.fulfillmentStatus.name

        // 9. Map shipping address
        val shippingAddress = ShippingAddressDto(
            name = ofr.shippingAddress.name,
            company = ofr.shippingAddress.company,
            addressLine1 = ofr.shippingAddress.addressLine1,
            addressLine2 = ofr.shippingAddress.addressLine2,
            city = ofr.shippingAddress.city,
            state = ofr.shippingAddress.state,
            country = ofr.shippingAddress.country,
            postalCode = ofr.shippingAddress.postalCode,
            phone = ofr.shippingAddress.phone
        )

        // 10. Map order value
        val orderValue = ofr.orderValue?.let {
            OrderValueDto(
                subtotal = it.subtotal,
                shipping = it.shipping,
                tax = it.tax,
                total = it.total,
                currency = it.currency
            )
        }

        return GinDataDto(
            owner = accountName,
            company = tenantName,
            ginNumber = ofr.ginNumber ?: "N/A",
            fulfillmentRequestId = ofr.fulfillmentId,
            externalOrderNumber = ofr.externalOrderNumber,
            clientReferenceNum = ofr.clientReferenceNum,
            dateIssued = ofr.fulfillmentRequestDate,
            transactionDate = ofr.updatedAt,
            status = currentStatus,
            carrier = ofr.shippingDetails.carrier,
            awbNumber = ofr.shippingDetails.awbNumber,
            trackingUrl = ofr.shippingDetails.trackingUrl,
            serviceType = ofr.shippingDetails.requestedServiceType?.name,
            shippingAddress = shippingAddress,
            customerName = ofr.customerInfo.name,
            customerEmail = ofr.customerInfo.email,
            customerPhone = ofr.customerInfo.phone,
            items = ginItems,
            totalOrdered = totalOrdered,
            totalPicked = totalPicked,
            totalShipped = totalShipped,
            totalCBM = totalCBM,
            totalWeight = totalWeight,
            orderValue = orderValue,
            generatedAt = LocalDateTime.now()
        )
    }

    /**
     * Process line items for GIN
     */
    private fun processLineItemsForGin(
        ofr: OrderFulfillmentRequest,
        skuDetailsMap: Map<Long, Map<String, Any>>,
        itemDimensionsByStorageId: Map<Long, StorageItemBarcodeResponse>,
        qbiDetailsMap: Map<String, QuantityInventoryResponse>
    ): List<GinItemDto> {
        logger.debug("Processing {} line items for GIN", ofr.lineItems.size)

        return ofr.lineItems.map { lineItem ->
            val itemCode: String
            val description: String
            val dimensions: StorageItemBarcodeResponse?

            when (lineItem.itemType) {
                ItemType.SKU_ITEM -> {
                    val skuId = lineItem.skuId ?: 0L
                    val skuDetails = skuDetailsMap[skuId]
                    itemCode = skuDetails?.get("skuCode") as? String ?: "SKU-$skuId"
                    description = skuDetails?.get("productTitle") as? String ?: "Unknown Product"

                    // Get dimensions from first allocated item OR from QBI
                    val firstStorageItemId = lineItem.allocatedItems.firstOrNull()?.storageItemId
                    dimensions = if (firstStorageItemId != null) {
                        itemDimensionsByStorageId[firstStorageItemId]
                    } else {
                        // Try to get from QBI for LOCATION/CONTAINER_QUANTITY_BASED
                        // Find first QBI that has dimensions (not just the first QBI)
                        lineItem.quantityInventoryReferences
                            .mapNotNull { ref -> getDimensionsFromQbi(qbiDetailsMap[ref.quantityInventoryId]) }
                            .firstOrNull()
                    }
                }
                ItemType.BOX, ItemType.PALLET -> {
                    // For quantity-based items, get item code and description from QBI
                    val firstQbiRef = lineItem.quantityInventoryReferences.firstOrNull()
                    val qbiDetails = firstQbiRef?.let { qbiDetailsMap[it.quantityInventoryId] }

                    // Item code: show parentContainerBarcode or N/A (don't show QBI ID)
                    itemCode = qbiDetails?.parentContainerBarcode
                        ?: lineItem.itemBarcode
                        ?: "N/A"

                    // Description: use QBI description if available, otherwise fallback to item type
                    description = qbiDetails?.description
                        ?: lineItem.itemType.name

                    // Get dimensions from allocated item OR from QBI
                    val firstStorageItemId = lineItem.allocatedItems.firstOrNull()?.storageItemId
                    dimensions = if (firstStorageItemId != null) {
                        itemDimensionsByStorageId[firstStorageItemId]
                    } else {
                        // Try to get from QBI for LOCATION/CONTAINER_QUANTITY_BASED
                        // Find first QBI that has dimensions (not just the first QBI)
                        lineItem.quantityInventoryReferences
                            .mapNotNull { ref -> getDimensionsFromQbi(qbiDetailsMap[ref.quantityInventoryId]) }
                            .firstOrNull()
                    }
                }
            }

            GinItemDto(
                itemCode = itemCode,
                description = description,
                itemType = lineItem.itemType.name,
                quantityOrdered = lineItem.quantityOrdered,
                quantityPicked = lineItem.quantityPicked,
                quantityShipped = lineItem.quantityShipped,
                length = dimensions?.lengthCm,
                width = dimensions?.widthCm,
                height = dimensions?.heightCm,
                cbm = calculateCbm(dimensions),
                weight = dimensions?.weightKg
            )
        }
    }

    /**
     * Fetch item dimensions from Inventory Service
     */
    private fun fetchItemDimensions(
        storageItemIds: List<Long>
    ): Map<Long, StorageItemBarcodeResponse> {
        val dimensionsByStorageItemId = mutableMapOf<Long, StorageItemBarcodeResponse>()

        if (storageItemIds.isEmpty()) {
            return dimensionsByStorageItemId
        }

        try {
            val response = inventoryServiceClient.getBarcodesByStorageItemIds(
                StorageItemBarcodesByIdsRequest(storageItemIds)
            )
            val data = response.data
            if (response.success && data != null) {
                data.forEach { item ->
                    dimensionsByStorageItemId[item.storageItemId] = item
                }
            } else {
                logger.warn("Failed to fetch item dimensions from inventory service")
            }
        } catch (e: Exception) {
            logger.error("Error fetching item dimensions", e)
        }

        return dimensionsByStorageItemId
    }

    /**
     * Fetch SKU details from Product Service in batch
     */
    private fun fetchSkuDetailsInBatch(skuIds: List<Long>): Map<Long, Map<String, Any>> {
        if (skuIds.isEmpty()) return emptyMap()

        return try {
            val response = productServiceClient.getBatchSkuDetails(
                BatchSkuRequest(
                    skuIds = skuIds,
                    fields = listOf("skuId", "skuCode", "productTitle")
                )
            )
            val data = response.data
            if (response.success && data != null) {
                logger.debug("Product service response: {}", data)
                @Suppress("UNCHECKED_CAST")
                val skusList = data["skus"] as? List<Map<String, Any>> ?: emptyList()

                // Convert list to map with skuId as key
                val skuDetailsMap = skusList.mapNotNull { skuData ->
                    val skuId = when (val id = skuData["skuId"]) {
                        is Int -> id.toLong()
                        is Long -> id
                        is Number -> id.toLong()
                        else -> null
                    }
                    if (skuId != null) {
                        logger.debug("SKU {} details: {}", skuId, skuData)
                        skuId to skuData
                    } else {
                        logger.warn("SKU data missing skuId: {}", skuData)
                        null
                    }
                }.toMap()
                logger.debug("Mapped SKU details for {} SKUs", skuDetailsMap.size)
                skuDetailsMap
            } else {
                logger.warn("Failed to fetch SKU details in batch")
                emptyMap()
            }
        } catch (e: Exception) {
            logger.error("Error fetching SKU details in batch", e)
            emptyMap()
        }
    }

    /**
     * Calculate CBM from dimensions if not provided
     * Formula: (Length × Width × Height) / 1,000,000
     */
    private fun calculateCbm(dimensions: StorageItemBarcodeResponse?): Double? {
        return if (dimensions?.volumeCbm != null) {
            dimensions.volumeCbm
        } else if (dimensions?.lengthCm != null && dimensions.widthCm != null && dimensions.heightCm != null) {
            (dimensions.lengthCm * dimensions.widthCm * dimensions.heightCm) / 1_000_000.0
        } else {
            null
        }
    }

    /**
     * Fetch QBI details from Inventory Service in batch
     */
    private fun fetchQbiDetails(qbiIds: List<String>): Map<String, QuantityInventoryResponse> {
        if (qbiIds.isEmpty()) return emptyMap()

        return try {
            val response = inventoryServiceClient.batchGetByIds(
                BatchGetQuantityInventoryRequest(qbiIds)
            )
            val data = response.data
            if (response.success && data != null) {
                data.associateBy { it.quantityInventoryId }
            } else {
                logger.warn("Failed to fetch QBI details from inventory service")
                emptyMap()
            }
        } catch (e: Exception) {
            logger.error("Error fetching QBI details", e)
            emptyMap()
        }
    }

    /**
     * Convert QBI dimensions to StorageItemBarcodeResponse format
     */
    private fun getDimensionsFromQbi(qbi: QuantityInventoryResponse?): StorageItemBarcodeResponse? {
        if (qbi == null || qbi.dimensions == null) return null

        val dims = qbi.dimensions
        return StorageItemBarcodeResponse(
            storageItemId = 0L, // Not applicable for QBI
            itemBarcode = qbi.quantityInventoryId,
            itemType = qbi.itemType,
            skuId = qbi.skuId,
            lengthCm = dims.lengthCm,
            widthCm = dims.widthCm,
            heightCm = dims.heightCm,
            volumeCbm = dims.cbm,
            weightKg = dims.weightGrams?.let { it / 1000.0 }, // Convert grams to kg
            parentItemId = null,
            parentItemBarcode = null
        )
    }
}
