package com.wmspro.order.service

import com.wmspro.order.client.InventoryServiceClient
import com.wmspro.order.client.ProductServiceClient
import com.wmspro.order.client.TaskServiceClient
import com.wmspro.order.dto.*
import com.wmspro.order.enums.*
import com.wmspro.order.exception.InsufficientInventoryException
import com.wmspro.order.exception.InvalidOrderRequestException
import com.wmspro.order.exception.OrderFulfillmentRequestNotFoundException
import com.wmspro.order.exception.TaskCreationFailedException
import com.wmspro.order.model.*
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class OrderFulfillmentService(
    private val ofrRepository: OrderFulfillmentRequestRepository,
    private val sequenceGeneratorService: SequenceGeneratorService,
    private val productServiceClient: ProductServiceClient,
    private val inventoryServiceClient: InventoryServiceClient,
    private val taskServiceClient: TaskServiceClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * API 133: Create Order Fulfillment Request with Task Generation
     *
     * Complex orchestration flow:
     * 1. Generate OFR ID and initialize model
     * 2. Fetch allocation methods for all SKUs from Product Service
     * 3. For each line item, call Inventory Service API 132 to get location allocations
     * 4. Build picking task details with location allocations
     * 5. Create PICKING or PICK_PACK_MOVE task in Task Service
     * 6. Update OFR status to ALLOCATED and save
     *
     * TODO: FIFO & LIFO has been applied as a temporary arrangement and the proper
     * implementation is awaited until further notice.
     */
    fun createOrderFulfillmentRequest(request: CreateOfrRequest, createdBy: String?, authToken: String): OrderFulfillmentRequest {
        logger.info("Creating Order Fulfillment Request for account: {}", request.accountId)

        // Step 1: Generate fulfillment ID
        val fulfillmentId = sequenceGeneratorService.generateOfrId()
        logger.info("Generated fulfillment ID: {}", fulfillmentId)

        // Step 2: Validate line items
        validateLineItems(request.lineItems)

        // Step 3: Initialize OFR model
        val ofr = OrderFulfillmentRequest(
            fulfillmentId = fulfillmentId,
            accountId = request.accountId,
            fulfillmentSource = request.fulfillmentSource,
            externalOrderId = request.externalOrderId,
            externalOrderNumber = request.externalOrderNumber,
            fulfillmentStatus = FulfillmentStatus.RECEIVED,
            priority = request.priority ?: Priority.STANDARD,
            executionApproach = request.executionApproach,
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
            shippingDetails = request.shippingDetails?.let {
                ShippingDetails(
                    carrier = it.carrier,
                    requestedServiceType = it.requestedServiceType
                )
            },
            orderValue = request.orderValue?.let {
                OrderValue(
                    subtotal = it.subtotal,
                    shipping = it.shipping,
                    tax = it.tax,
                    total = it.total,
                    currency = it.currency
                )
            },
            notes = request.notes,
            tags = request.tags ?: listOf(),
            customFields = request.customFields ?: mapOf(),
            createdBy = createdBy
        )

        // Step 4: Build line items
        val lineItems = mutableListOf<LineItem>()
        request.lineItems.forEach { lineItemDto ->
            val lineItemId = sequenceGeneratorService.generateLineItemId()
            lineItems.add(
                LineItem(
                    lineItemId = lineItemId,
                    itemType = lineItemDto.itemType,
                    skuId = lineItemDto.skuId,
                    itemBarcode = lineItemDto.itemBarcode,
                    quantityOrdered = lineItemDto.quantityOrdered
                )
            )
        }
        ofr.lineItems.addAll(lineItems)

        // Step 5: Extract SKU IDs and fetch allocation methods from Product Service
        val skuLineItems = lineItems.filter { it.itemType == ItemType.SKU_ITEM && it.skuId != null }
        val skuIds = skuLineItems.mapNotNull { it.skuId }

        val skuAllocationMethods = if (skuIds.isNotEmpty()) {
            fetchSkuAllocationMethods(skuIds)
        } else {
            emptyMap()
        }

        // Step 6: For each line item, fetch location allocations from Inventory Service
        val pickingItems = mutableListOf<PickingItemDto>()

        for (lineItem in lineItems) {
            when (lineItem.itemType) {
                ItemType.SKU_ITEM -> {
                    val skuId = lineItem.skuId
                        ?: throw InvalidOrderRequestException("SKU ID is required for SKU_ITEM type")

                    val allocationMethod = skuAllocationMethods[skuId]
                        ?: throw InvalidOrderRequestException("Allocation method not found for SKU ID: $skuId")

                    // Store allocation method in line item
                    lineItem.allocationMethod = allocationMethod

                    // Call Inventory Service API 132
                    val locationAllocation = fetchLocationAllocation(
                        skuId = skuId,
                        requiredQuantity = lineItem.quantityOrdered,
                        allocationMethod = allocationMethod
                    )

                    if (locationAllocation.locations.isEmpty()) {
                        throw InsufficientInventoryException("Insufficient inventory for SKU ID: $skuId")
                    }

                    // Build pickup locations
                    val pickupLocations = locationAllocation.locations.map { loc ->
                        PickupLocationDto(
                            locationCode = loc.locationCode,
                            itemRange = loc.itemBarcodes.joinToString(", ")
                        )
                    }

                    pickingItems.add(
                        PickingItemDto(
                            skuId = skuId,
                            itemType = ItemType.SKU_ITEM.name,
                            totalQuantityRequired = lineItem.quantityOrdered,
                            pickMethod = allocationMethod.name,
                            pickupLocations = pickupLocations
                        )
                    )
                }

                ItemType.BOX, ItemType.PALLET -> {
                    val itemBarcode = lineItem.itemBarcode
                        ?: throw InvalidOrderRequestException("Item barcode is required for ${lineItem.itemType} type")

                    // Call Inventory Service API 132 with itemBarcode
                    val locationAllocation = fetchLocationAllocation(
                        itemBarcode = itemBarcode,
                        requiredQuantity = 1,
                        allocationMethod = AllocationMethod.RANDOM
                    )

                    if (locationAllocation.locations.isEmpty()) {
                        throw InsufficientInventoryException("Item not found: $itemBarcode")
                    }

                    // Build pickup locations
                    val pickupLocations = locationAllocation.locations.map { loc ->
                        PickupLocationDto(
                            locationCode = loc.locationCode,
                            itemRange = loc.itemBarcodes.joinToString(", ")
                        )
                    }

                    pickingItems.add(
                        PickingItemDto(
                            itemBarcode = itemBarcode,
                            itemType = lineItem.itemType.name,
                            pickMethod = "RANDOM",
                            pickupLocations = pickupLocations
                        )
                    )
                }
            }
        }

        // Step 7: Create task based on execution approach
        // Map OFR Priority to Task Priority
        val taskPriority = when (request.priority) {
            Priority.URGENT -> TaskPriority.URGENT
            Priority.STANDARD -> TaskPriority.NORMAL
            Priority.ECONOMY -> TaskPriority.LOW
            null -> TaskPriority.NORMAL
        }

        val taskRequest = when (request.executionApproach) {
            ExecutionApproach.SEPARATED_PICKING -> {
                CreateTaskRequest(
                    taskType = "PICKING",
                    warehouseId = "WH-001", // TODO: Get from request or tenant config
                    accountIds = listOf(request.accountId),
                    priority = taskPriority,
                    pickingDetails = PickingDetailsDto(
                        fulfillmentRequestId = fulfillmentId,
                        itemsToPick = pickingItems
                    )
                )
            }

            ExecutionApproach.PICK_PACK_MOVE_TOGETHER -> {
                CreateTaskRequest(
                    taskType = "PICK_PACK_MOVE",
                    warehouseId = "WH-001", // TODO: Get from request or tenant config
                    accountIds = listOf(request.accountId),
                    priority = taskPriority,
                    pickPackMoveDetails = PickPackMoveDetailsDto(
                        fulfillmentRequestId = fulfillmentId,
                        itemsToPick = pickingItems
                    )
                )
            }
        }

        // Step 8: Call Task Service to create task
        val taskResponse = try {
            taskServiceClient.createTask(taskRequest, authToken)
        } catch (e: Exception) {
            logger.error("Failed to create task for OFR: $fulfillmentId", e)
            throw TaskCreationFailedException("Task creation failed: ${e.message}")
        }

        if (!taskResponse.success || taskResponse.data == null) {
            throw TaskCreationFailedException("Task creation failed: ${taskResponse.message}")
        }

        val taskData = taskResponse.data!!
        logger.info("Task created successfully: {}", taskData.taskCode)

        // Step 9: Update OFR with task ID and status
        when (request.executionApproach) {
            ExecutionApproach.SEPARATED_PICKING -> {
                ofr.pickingTaskId = taskData.taskCode
            }

            ExecutionApproach.PICK_PACK_MOVE_TOGETHER -> {
                ofr.pickPackMoveTaskId = taskData.taskCode
            }
        }

        ofr.fulfillmentStatus = FulfillmentStatus.ALLOCATED

        // Add status history
        ofr.statusHistory.add(
            StatusHistory(
                status = FulfillmentStatus.RECEIVED,
                timestamp = LocalDateTime.now(),
                user = createdBy,
                automated = true
            )
        )
        ofr.statusHistory.add(
            StatusHistory(
                status = FulfillmentStatus.ALLOCATED,
                timestamp = LocalDateTime.now(),
                user = createdBy,
                automated = true,
                notes = "Task ${taskData.taskCode} created successfully"
            )
        )

        // Step 10: Save OFR
        val savedOfr = ofrRepository.save(ofr)
        logger.info("Order Fulfillment Request created successfully: {}", savedOfr.fulfillmentId)

        return savedOfr
    }

    /**
     * Fetch SKU allocation methods from Product Service
     */
    private fun fetchSkuAllocationMethods(skuIds: List<Long>): Map<Long, AllocationMethod> {
        logger.debug("Fetching allocation methods for {} SKUs", skuIds.size)

        val batchRequest = BatchSkuRequest(
            skuIds = skuIds,
            fields = listOf("allocationMethod")
        )

        val response = productServiceClient.getBatchSkuDetails(batchRequest)

        if (!response.success || response.data == null) {
            throw InvalidOrderRequestException("Failed to fetch SKU details: ${response.message}")
        }

        // Parse allocation methods from response
        // Response structure: {skus=[{skuId=4, allocationMethod=RANDOM}, ...], totalRequested=2, totalFound=2, missingCount=0}
        val result = mutableMapOf<Long, AllocationMethod>()
        val responseData = response.data!!

        val skusList = responseData["skus"]
        if (skusList is List<*>) {
            skusList.forEach { skuData ->
                if (skuData is Map<*, *>) {
                    val skuId = (skuData["skuId"] as? Number)?.toLong()
                    val allocationMethodStr = skuData["allocationMethod"] as? String

                    if (skuId != null && allocationMethodStr != null) {
                        result[skuId] = AllocationMethod.valueOf(allocationMethodStr)
                    }
                }
            }
        }

        return result
    }

    /**
     * Fetch location allocation from Inventory Service (API 132)
     */
    private fun fetchLocationAllocation(
        skuId: Long? = null,
        itemBarcode: String? = null,
        requiredQuantity: Int,
        allocationMethod: AllocationMethod
    ): LocationAllocationResponse {
        logger.debug("Fetching location allocation - skuId: {}, itemBarcode: {}, quantity: {}, method: {}",
            skuId, itemBarcode, requiredQuantity, allocationMethod)

        val response = inventoryServiceClient.getLocationWiseQuantityNumbers(
            skuId = skuId,
            itemBarcode = itemBarcode,
            requiredQuantity = requiredQuantity,
            allocationMethod = allocationMethod.name
        )

        if (!response.success || response.data == null) {
            logger.warn("Inventory allocation failed: {}", response.message)
            return LocationAllocationResponse(emptyList(), 0, allocationMethod.name)
        }

        val locationData = response.data!!
        return locationData
    }

    /**
     * Validate line items
     */
    private fun validateLineItems(lineItems: List<LineItemDto>) {
        if (lineItems.isEmpty()) {
            throw InvalidOrderRequestException("Line items cannot be empty")
        }

        lineItems.forEach { lineItem ->
            when (lineItem.itemType) {
                ItemType.SKU_ITEM -> {
                    if (lineItem.skuId == null) {
                        throw InvalidOrderRequestException("SKU ID is required for SKU_ITEM type")
                    }
                }

                ItemType.BOX, ItemType.PALLET -> {
                    if (lineItem.itemBarcode.isNullOrBlank()) {
                        throw InvalidOrderRequestException("Item barcode is required for ${lineItem.itemType} type")
                    }
                }
            }
        }
    }

    /**
     * Get all OFRs with optional filtering and pagination
     */
    fun getAllOFRs(
        accountId: Long?,
        fulfillmentStatus: FulfillmentStatus?,
        page: Int,
        size: Int
    ): Page<OrderFulfillmentRequest> {
        logger.info("Getting all OFRs - accountId: {}, status: {}, page: {}, size: {}",
            accountId, fulfillmentStatus, page, size)

        val pageable: Pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))

        return when {
            accountId != null && fulfillmentStatus != null -> {
                ofrRepository.findByAccountIdAndFulfillmentStatus(accountId, fulfillmentStatus, pageable)
            }

            accountId != null -> {
                ofrRepository.findByAccountId(accountId, pageable)
            }

            fulfillmentStatus != null -> {
                ofrRepository.findByFulfillmentStatus(fulfillmentStatus, pageable)
            }

            else -> {
                ofrRepository.findAllBy(pageable)
            }
        }
    }

    /**
     * Get OFR by ID
     */
    fun getOfrById(fulfillmentId: String): OrderFulfillmentRequest {
        logger.info("Getting OFR by ID: {}", fulfillmentId)

        return ofrRepository.findByFulfillmentId(fulfillmentId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentId") }
    }
}
