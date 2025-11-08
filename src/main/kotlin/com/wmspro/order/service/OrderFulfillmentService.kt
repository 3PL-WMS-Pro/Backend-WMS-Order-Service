package com.wmspro.order.service

import com.wmspro.order.client.InventoryServiceClient
import com.wmspro.order.client.ProductServiceClient
import com.wmspro.order.client.StorageItemIdsByBarcodesRequest
import com.wmspro.order.client.TaskServiceClient
import com.wmspro.order.dto.*
import com.wmspro.order.enums.*
import com.wmspro.order.exception.InsufficientInventoryException
import com.wmspro.order.exception.InvalidOrderRequestException
import com.wmspro.order.exception.OrderFulfillmentRequestNotFoundException
import com.wmspro.order.exception.TaskCreationFailedException
import com.wmspro.order.model.*
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import jakarta.validation.ValidationException
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
    private val taskServiceClient: TaskServiceClient,
    private val jwtTokenExtractor: com.wmspro.common.jwt.JwtTokenExtractor,
    private val accountService: com.wmspro.common.service.AccountService
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
     *  implementation is awaited until further notice.
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
            shippingDetails = request.shippingDetails.let {
                ShippingDetails(
                    awbCondition = it?.awbCondition ?: AwbCondition.CREATE_FOR_CUSTOMER,
                    carrier = it?.carrier,
                    requestedServiceType = it?.requestedServiceType
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

                    // Fetch the Storage Item ID to save in the database.
                    val storageItemIdResponse = try {
                        inventoryServiceClient.getStorageItemIdsByBarcodes(
                            StorageItemIdsByBarcodesRequest(listOf(itemBarcode))
                        )
                    } catch (e: Exception) {
                        logger.error("Error fetching storage item ID for barcode: $itemBarcode", e)
                        throw ValidationException("Failed to fetch storage item ID: ${e.message}")
                    }

                    val storageItemId = storageItemIdResponse.data?.firstOrNull()?.storageItemId
                        ?: throw ValidationException("Storage item ID not found for barcode: $itemBarcode")

                    pickingItems.add(
                        PickingItemDto(
                            storageItemId = storageItemId,
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

            ExecutionApproach.DIRECT_PROCESSING -> {
                throw InvalidOrderRequestException("DIRECT_PROCESSING approach should use the createDirectOfrAndProcess method instead")
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

            ExecutionApproach.DIRECT_PROCESSING -> {
                // Should never reach here
                throw InvalidOrderRequestException("DIRECT_PROCESSING approach should use the createDirectOfrAndProcess method instead")
            }
        }

        ofr.fulfillmentStatus = FulfillmentStatus.ALLOCATED

        // Add status history
        ofr.statusHistory.add(
            StatusHistory(
                status = FulfillmentStatus.RECEIVED,
                timestamp = LocalDateTime.now(),
                user = createdBy,
                automated = true,
                currentStatus = false  // This is an old status
            )
        )
        ofr.statusHistory.add(
            StatusHistory(
                status = FulfillmentStatus.ALLOCATED,
                timestamp = LocalDateTime.now(),
                user = createdBy,
                automated = true,
                notes = "Task ${taskData.taskCode} created successfully",
                currentStatus = true  // This is the current status
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

    /**
     * API 141: Change OFR Status to "PICKUP_DONE"
     * Updates OFR status after picking completion and creates Pack_Move task if needed
     */
    fun changeOfrStatusToPickupDone(
        fulfillmentRequestId: String,
        request: ChangeOfrStatusToPickupDoneRequest,
        authToken: String
    ): ChangeOfrStatusToPickupDoneResponse {
        logger.info("Changing OFR status to PICKUP_DONE for: $fulfillmentRequestId")

        // Step 1: Fetch OFR
        val ofr = ofrRepository.findByFulfillmentId(fulfillmentRequestId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentRequestId") }

        // Step 2: Update line_items and allocated_items based on items_to_pick
        for (pickedItem in request.itemsToPick) {
            // Find matching line item
            val lineItem = ofr.lineItems.find { lineItem ->
                when (lineItem.itemType) {
                    ItemType.BOX, ItemType.PALLET -> lineItem.itemBarcode == pickedItem.itemBarcode
                    ItemType.SKU_ITEM -> lineItem.skuId == pickedItem.skuId
                }
            }

            if (lineItem != null) {
                // Update quantityPicked in line item
                when (lineItem.itemType) {
                    ItemType.BOX, ItemType.PALLET -> if (pickedItem.picked) lineItem.quantityPicked = 1
                    ItemType.SKU_ITEM -> lineItem.quantityPicked = lineItem.quantityPicked + 1
                }

                // Update or create allocated_items
                if (pickedItem.storageItemId != null) {
                    val existingAllocated = lineItem.allocatedItems.find { it.storageItemId == pickedItem.storageItemId }

                    if (existingAllocated != null) {
                        // Update existing allocated item
                        existingAllocated.picked = pickedItem.picked
                        existingAllocated.pickedAt = pickedItem.pickedAt
                        existingAllocated.pickedBy = jwtTokenExtractor.extractUsername(authToken)
                    } else {
                        // Create new allocated item
                        lineItem.allocatedItems.add(
                            AllocatedItem(
                                storageItemId = pickedItem.storageItemId,
                                location = "PACKING_ZONE", // Will be updated by inventory service
                                picked = pickedItem.picked,
                                pickedAt = pickedItem.pickedAt,
                                pickedBy = jwtTokenExtractor.extractUsername(authToken)
                            )
                        )
                    }
                }
            }
        }

        // Step 3: Update OFR status to PICKED
        ofr.fulfillmentStatus = FulfillmentStatus.PICKED

        // Step 4: Mark all existing status history entries as not current by recreating them
        val updatedHistory = ofr.statusHistory.map { history ->
            history.copy(currentStatus = false)
        }.toMutableList()
        ofr.statusHistory.clear()
        ofr.statusHistory.addAll(updatedHistory)

        // Step 5: Add status_history entry with currentStatus = true
        val username = jwtTokenExtractor.extractUsername(authToken) ?: "SYSTEM"
        ofr.statusHistory.add(
            StatusHistory(
                status = FulfillmentStatus.PICKED,
                timestamp = LocalDateTime.now(),
                user = username,
                automated = true,
                notes = null,
                currentStatus = true  // This is the current status
            )
        )

        // Step 5: Save OFR
        val updatedOfr = ofrRepository.save(ofr)
        logger.info("OFR status updated to PICKED: $fulfillmentRequestId")

        // Step 6: Check execution_approach and create Pack_Move task if needed
        var nextTask: NextTaskDto? = null

        if (ofr.executionApproach == ExecutionApproach.SEPARATED_PICKING) {
            logger.info("Creating PACK_MOVE task for SEPARATED_PICKING approach")

            try {
                // Build Pack Move task request
                val packMoveItems = request.itemsToPick.map { item ->
                    PackMoveItemDto(
                        storageItemId = item.storageItemId ?: 0L,
                        itemBarcode = item.itemBarcode ?: "",
                        skuId = item.skuId,
                        itemType = item.itemType,
                        packingType = "Standard Packaging (Up-to the user)" // TODO: Need to fetch the packagingType from the SKU Details
                    )
                }

                val createTaskRequest = CreateTaskRequest(
                    taskType = "PACK_MOVE",
                    warehouseId = "DEFAULT", // TODO: Get from OFR context
                    assignedTo = null,
                    accountIds = listOf(ofr.accountId),
                    priority = when (ofr.priority) {
                        Priority.URGENT -> TaskPriority.HIGH
                        Priority.STANDARD -> TaskPriority.NORMAL
                        Priority.ECONOMY -> TaskPriority.LOW
                    },
                    packMoveDetails = PackMoveDetailsDto(
                        fulfillmentRequestId = fulfillmentRequestId,
                        itemsToVerify = packMoveItems,
                        awbCondition = ofr.shippingDetails.awbCondition
                    )
                )

                val taskResponse = taskServiceClient.createTask(createTaskRequest, authToken)

                if (taskResponse.success) {
                    val taskData = taskResponse.data
                    if (taskData != null) {
                        nextTask = NextTaskDto(
                            taskCode = taskData.taskCode,
                            taskType = "PACK_MOVE"
                        )

                        // Update OFR with Pack_Move task reference
                        updatedOfr.packMoveTaskId = taskData.taskCode
                        ofrRepository.save(updatedOfr)

                        logger.info("PACK_MOVE task created: ${taskData.taskCode}")
                    } else {
                        logger.error("Failed to create PACK_MOVE task: Task data is null")
                        throw TaskCreationFailedException("Failed to create PACK_MOVE task: Task data is null")
                    }
                } else {
                    logger.error("Failed to create PACK_MOVE task: ${taskResponse.message}")
                    throw TaskCreationFailedException("Failed to create PACK_MOVE task: ${taskResponse.message}")
                }
            } catch (e: Exception) {
                logger.error("Error creating PACK_MOVE task: ${e.message}", e)
                throw TaskCreationFailedException("Error creating PACK_MOVE task: ${e.message}")
            }
        }

        return ChangeOfrStatusToPickupDoneResponse(
            fulfillmentRequestId = updatedOfr.fulfillmentId,
            fulfillmentStatus = updatedOfr.fulfillmentStatus.name,
            updatedAt = LocalDateTime.now(),
            nextTask = nextTask
        )
    }

    /**
     * Get OFRs by Stage for Web List Views
     * All filtering is done at database level using MongoTemplate
     */
    fun getOfrsByStage(
        stage: OfrStage,
        page: Int,
        size: Int,
        searchTerm: String?,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
        accountId: Long?,
        authToken: String
    ): PageResponse<OfrListItemResponse> {
        logger.info("Fetching OFRs for stage: {} with page: {}, size: {}", stage, page, size)

        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"))

        // Fetch OFRs with all filters applied at database level
        val ofrPage = ofrRepository.findOfrsByStageWithFilters(
            stage = stage,
            accountId = accountId,
            searchTerm = searchTerm,
            dateFrom = dateFrom,
            dateTo = dateTo,
            pageable = pageable
        )

        // Enrich with account names
        val accountIds = ofrPage.content.map { it.accountId }.distinct()
        val accountNames = if (accountIds.isNotEmpty()) {
            accountService.fetchAccountNames(accountIds, authToken)
        } else {
            emptyMap()
        }

        // Convert to response DTOs
        val responseItems = ofrPage.content.map { ofr ->
            toOfrListItemResponse(ofr, accountNames[ofr.accountId.toString()])
        }

        return PageResponse(
            data = responseItems,
            page = page,
            limit = size,
            totalItems = ofrPage.totalElements,
            totalPages = ofrPage.totalPages,
            hasNext = ofrPage.hasNext(),
            hasPrevious = ofrPage.hasPrevious()
        )
    }

    /**
     * Get OFR Stage Summary (counts for all stages)
     * All counting is done at database level using MongoTemplate
     */
    fun getOfrStageSummary(accountId: Long?): OfrStageSummaryResponse {
        logger.info("Fetching OFR stage summary" + if (accountId != null) " for account: $accountId" else "")

        // Get counts for all stages using database-level queries
        return OfrStageSummaryResponse(
            pickingPending = ofrRepository.countOfrsByStage(OfrStage.PICKING_PENDING, accountId),
            packMovePending = ofrRepository.countOfrsByStage(OfrStage.PACK_MOVE_PENDING, accountId),
            pickPackMovePending = ofrRepository.countOfrsByStage(OfrStage.PICK_PACK_MOVE_PENDING, accountId),
            readyToDispatch = ofrRepository.countOfrsByStage(OfrStage.READY_TO_DISPATCH, accountId),
            loadingDoneGinPending = ofrRepository.countOfrsByStage(OfrStage.LOADING_DONE_GIN_PENDING, accountId),
            ginSent = ofrRepository.countOfrsByStage(OfrStage.GIN_SENT, accountId)
        )
    }

    /**
     * Convert OFR to OfrListItemResponse
     */
    private fun toOfrListItemResponse(
        ofr: OrderFulfillmentRequest,
        accountName: String?
    ): OfrListItemResponse {
        return OfrListItemResponse(
            fulfillmentId = ofr.fulfillmentId,
            createdAt = ofr.createdAt,
            updatedAt = ofr.updatedAt,
            customerName = ofr.customerInfo.name,
            accountName = accountName,
            pickingId = ofr.pickingTaskId,
            packMoveId = ofr.packMoveTaskId,
            pickPackMoveId = ofr.pickPackMoveTaskId,
            loadingId = ofr.loadingTaskId,
            awb = ofr.shippingDetails.awbNumber,
            gin = ofr.ginNumber,
            items = calculateItemsCount(ofr),
            locations = calculateLocationsCount(ofr),
            packages = ofr.packages.size
        )
    }

    /**
     * Calculate total items count (sum of quantityOrdered)
     */
    private fun calculateItemsCount(ofr: OrderFulfillmentRequest): Int {
        return ofr.lineItems.sumOf { it.quantityOrdered }
    }

    /**
     * Calculate distinct locations count
     */
    private fun calculateLocationsCount(ofr: OrderFulfillmentRequest): Int {
        val locations = mutableSetOf<String>()
        ofr.lineItems.forEach { lineItem ->
            lineItem.allocatedItems.forEach { allocatedItem ->
                locations.add(allocatedItem.location)
            }
        }
        return locations.size
    }

    /**
     * API 154: Create AWB For All Packages
     *
     * TODO: This AWB generation is completely hardcoded. Ideally AWB needs to be generated
     *  dynamically and properly using the Shipping-Micro-Service. This is a placeholder
     *  implementation until Shipping-Service integration is complete.
     *
     * TODO: Once Shipping-Service is integrated, this method should:
     *  1. Call Shipping-Service with package details
     *  2. Receive real AWB number, tracking URL, and PDF documents
     *  3. Update OFR with actual shipping details
     */
    @Transactional
    fun createAwbForAllPackages(fulfillmentRequestId: String): AwbConfigurationResponse {
        logger.info("API 154: Creating AWB for fulfillment request: $fulfillmentRequestId")

        // Step 1: Fetch OFR
        val ofr = ofrRepository.findByFulfillmentId(fulfillmentRequestId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentRequestId") }

        logger.warn("Generating hardcoded AWB - Shipping Service integration pending")

        // Step 2: Generate hardcoded AWB data (placeholder until Shipping-Service integration)
        val timestamp = System.currentTimeMillis()
        val randomAwbNumber = (100000000..999999999).random()

        val shipmentId = "SHIP-2025-${String.format("%06d", timestamp % 1000000)}"
        val awbNumber = "AWB-$randomAwbNumber"
        val carrier = "FIRST_FLIGHT_COURIER"
        val createdViaApi = true
        val requestedServiceType = ServiceType.EXPRESS.name
        val selectedServiceCode = "EXP-001"
        val trackingUrl = "https://track.example.com/$awbNumber"

        // Minimal valid base64 PDF placeholder
        val hardcodedPdfBase64 = "JVBERi0xLjQKJeLjz9MKMyAwIG9iago8PC9UeXBlL1BhZ2UvUGFyZW50IDIgMCBSL01lZGlhQm94WzAgMCA2MTIgNzkyXS9Db250ZW50cyA0IDAgUj4+CmVuZG9iago0IDAgb2JqCjw8L0xlbmd0aCAzOD4+CnN0cmVhbQpCVAovRjEgMTIgVGYKMTAwIDcwMCBUZAooSGFyZGNvZGVkIFBsYWNlaG9sZGVyIFBERikgVGoKRVQKZW5kc3RyZWFtCmVuZG9iag=="

        val awbConfiguration = AwbConfigurationResponse(
            shipmentId = shipmentId,
            awbNumber = awbNumber,
            carrier = carrier,
            createdViaApi = createdViaApi,
            requestedServiceType = requestedServiceType,
            selectedServiceCode = selectedServiceCode,
            trackingUrl = trackingUrl,
            shippingLabelPdf = hardcodedPdfBase64,
            awbPdf = hardcodedPdfBase64
        )

        // Step 3: Update OFR shipping_details with AWB information
        val updatedShippingDetails = ofr.shippingDetails.copy(
            carrier = carrier,
            requestedServiceType = ServiceType.valueOf(requestedServiceType),
            selectedServiceCode = selectedServiceCode,
            shipmentId = shipmentId,
            awbNumber = awbNumber,
            awbPdf = hardcodedPdfBase64,
            trackingUrl = trackingUrl,
            shippingLabelPdf = hardcodedPdfBase64
        )

        val updatedOfr = ofr.copy(
            shippingDetails = updatedShippingDetails,
            updatedAt = LocalDateTime.now()
        )

        // Step 4: Save updated OFR
        ofrRepository.save(updatedOfr)

        logger.info("AWB generated successfully (hardcoded): $awbNumber for OFR: $fulfillmentRequestId")

        // Step 5: Return AWB configuration
        return awbConfiguration
    }

    /**
     * API 160: Change OFR Status to READY_TO_SHIP
     *
     * Generates GIN (Goods Issue Note) if not exists, updates OFR status to READY_TO_SHIP,
     * and calls Inventory-Service API 127 to update all item locations to dispatch area.
     *
     * This is the final step in pack-move completion orchestration:
     * 1. Generate or retrieve GIN number
     * 2. Update OFR status to READY_TO_SHIP
     * 3. For each package → for each assigned item → update inventory location
     * 4. Save updated OFR
     *
     * Note: shipmentStatus field update is skipped as per API clarification.
     * This was mentioned in the API Specsheet but the field doesn't exist in the OFR model.
     */
    @Transactional
    fun changeOfrStatusToReadyToShip(fulfillmentRequestId: String, authToken: String): String {
        logger.info("API 160: Changing OFR status to READY_TO_SHIP for: $fulfillmentRequestId")

        // Step 1: Fetch OFR
        var ofr = ofrRepository.findByFulfillmentId(fulfillmentRequestId)
            .orElseThrow { OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentRequestId") }

        // Step 2: Generate GIN if not exists
        val ginNumber = if (ofr.ginNumber.isNullOrBlank()) {
            try {
                val generatedGin = sequenceGeneratorService.generateGinNumber()
                logger.info("Generated new GIN: $generatedGin for OFR: $fulfillmentRequestId")
                generatedGin
            } catch (e: Exception) {
                logger.error("Failed to generate GIN: ${e.message}", e)
                throw ValidationException("Failed to generate GIN number: ${e.message}")
            }
        } else {
            logger.info("Using existing GIN: ${ofr.ginNumber} for OFR: $fulfillmentRequestId")
            ofr.ginNumber
        }

        // Step 3: Update OFR status and GIN
        val updatedStatusHistory = ofr.statusHistory.toMutableList().apply {
            // Set all previous entries to current_status = false
            forEach { it.copy(currentStatus = false) }
            // Add new status entry
            add(
                StatusHistory(
                    status = FulfillmentStatus.READY_TO_SHIP,
                    timestamp = LocalDateTime.now(),
                    user = jwtTokenExtractor.extractUsername(authToken),
                    automated = false,
                    notes = "Pack move completed, ready for shipping",
                    currentStatus = true
                )
            )
        }

        ofr = ofr.copy(
            fulfillmentStatus = FulfillmentStatus.READY_TO_SHIP,
            ginNumber = ginNumber,
            statusHistory = updatedStatusHistory,
            updatedAt = LocalDateTime.now()
        )

        // Step 4: Call Inventory-Service API 127 for each package's items
        logger.info("Updating inventory locations for ${ofr.packages.size} packages")
        var inventoryUpdateSuccessCount = 0
        var inventoryUpdateFailureCount = 0

        for (pkg in ofr.packages) {
            val dispatchArea = pkg.dispatchArea ?: pkg.dispatchAreaBarcode
            if (dispatchArea.isNullOrBlank()) {
                logger.warn("Package ${pkg.packageId} has no dispatch area defined, skipping inventory updates")
                continue
            }

            logger.info("Updating locations for ${pkg.assignedItems.size} items in package ${pkg.packageId} to $dispatchArea")

            for (assignedItem in pkg.assignedItems) {
                // Update inventory location for this item
                try {
                    val response = inventoryServiceClient.changeStorageItemLocation(
                        itemBarcode = assignedItem.itemBarcode,
                        request = com.wmspro.order.client.ChangeLocationRequest(
                            newLocation = dispatchArea,
                            taskCode = ofr.packMoveTaskId ?: "PACK_MOVE_COMPLETED",
                            action = "MOVED",
                            notes = "Moved to dispatch area after pack move completion",
                            reason = "Pack move completed, ready for shipping"
                        ),
                        authToken = authToken
                    )

                    if (response.success) {
                        inventoryUpdateSuccessCount++
                        logger.info("Successfully updated location for item: ${assignedItem.itemBarcode} to $dispatchArea")
                    } else {
                        inventoryUpdateFailureCount++
                        logger.error("Inventory API returned error for ${assignedItem.itemBarcode}: ${response.message}")
                    }
                } catch (e: Exception) {
                    // Non-blocking: Log error but continue
                    inventoryUpdateFailureCount++
                    logger.error("Failed to update inventory location for ${assignedItem.itemBarcode}: ${e.message}", e)
                }
            }
        }

        logger.info("Inventory updates completed: $inventoryUpdateSuccessCount succeeded, $inventoryUpdateFailureCount failed")

        // Step 5: Save updated OFR
        ofrRepository.save(ofr)
        logger.info("OFR $fulfillmentRequestId status changed to READY_TO_SHIP with GIN: $ginNumber")

        // Return success message
        val message = if (inventoryUpdateFailureCount > 0) {
            "OFR status changed to READY_TO_SHIP with GIN $ginNumber. Warning: $inventoryUpdateFailureCount inventory location updates failed."
        } else {
            "OFR status changed to READY_TO_SHIP with GIN $ginNumber. All inventory locations updated successfully."
        }

        return message
    }

    /**
     * API 183: Change OFR Status to SHIPPED
     * Updates OFR with loading task details and triggers inventory location updates
     */
    @Transactional
    fun changeOfrStatusToShipped(
        fulfillmentRequestId: String,
        loadingTaskId: String,
        packagesToLoad: List<PackageToLoadDto>,
        authToken: String
    ): String {
        val tenantId = com.wmspro.common.tenant.TenantContext.requireCurrentTenant()
        logger.info("API 183: Changing OFR $fulfillmentRequestId to SHIPPED from loading task $loadingTaskId, tenant: $tenantId")

        // Step 1: Query and validate OFR
        val ofrRetrieved = ofrRepository.findByFulfillmentId(fulfillmentRequestId)
            ?: throw OrderFulfillmentRequestNotFoundException("Order Fulfillment Request not found: $fulfillmentRequestId")

        val ofr = ofrRetrieved.get()

        // Step 2: Update packages with loading details
        for (packageToLoad in packagesToLoad) {
            val packageInOfr = ofr.packages.find {
                it.packageId == packageToLoad.packageId || it.packageBarcode == packageToLoad.packageBarcode
            }

            if (packageInOfr != null) {
                packageInOfr.loadedOnTruck = true
                logger.info("Updated package ${packageToLoad.packageId} as loaded on truck")
            } else {
                logger.warn("Package ${packageToLoad.packageId} not found in OFR $fulfillmentRequestId")
            }
        }

        // Step 3: Update OFR fields
        ofr.loadingTaskId = loadingTaskId
        ofr.fulfillmentStatus = FulfillmentStatus.SHIPPED

        // Step 4: Add status history entry
        val username = jwtTokenExtractor.extractUsername(authToken) ?: "SYSTEM"

        // Set previous status to non-current (create new objects since currentStatus is immutable)
        val updatedHistory = ofr.statusHistory.map { history ->
            history.copy(currentStatus = false)
        }.toMutableList()

        // Add new SHIPPED status
        updatedHistory.add(
            StatusHistory(
                status = FulfillmentStatus.SHIPPED,
                timestamp = LocalDateTime.now(),
                user = username,
                automated = false,
                notes = "Loaded onto truck for loading task $loadingTaskId",
                currentStatus = true
            )
        )

        // Clear and replace status history
        ofr.statusHistory.clear()
        ofr.statusHistory.addAll(updatedHistory)

        // Step 5: Save updated OFR
        ofrRepository.save(ofr)
        logger.info("OFR $fulfillmentRequestId status updated to SHIPPED")

        // Step 6: CRITICAL - Inventory Location Updates
        logger.info("Starting inventory location updates for OFR $fulfillmentRequestId")

        var inventoryUpdateSuccessCount = 0
        var inventoryUpdateFailureCount = 0

        for (pkg in ofr.packages) {
            for (assignedItem in pkg.assignedItems) {
                try {
                    val changeLocationRequest = com.wmspro.order.client.ChangeLocationRequest(
                        newLocation = "IN_TRANSIT", // or "CUSTOMER" based on business logic
                        taskCode = loadingTaskId,
                        action = "SHIPPED",
                        notes = "Shipped via OFR $fulfillmentRequestId",
                        reason = "Loading task completed"
                    )

                    val response = inventoryServiceClient.changeStorageItemLocation(
                        assignedItem.itemBarcode,
                        changeLocationRequest,
                        authToken
                    )

                    if (response.success) {
                        inventoryUpdateSuccessCount++
                        logger.info("Updated inventory location for ${assignedItem.itemBarcode} to IN_TRANSIT")
                    } else {
                        inventoryUpdateFailureCount++
                        logger.error("Inventory API returned error for ${assignedItem.itemBarcode}: ${response.message}")
                    }
                } catch (e: Exception) {
                    // Non-blocking: Log error but continue
                    inventoryUpdateFailureCount++
                    logger.error("Failed to update inventory location for ${assignedItem.itemBarcode}: ${e.message}", e)
                }
            }
        }

        logger.info("Inventory updates completed: $inventoryUpdateSuccessCount succeeded, $inventoryUpdateFailureCount failed")

        // Return success message
        val message = if (inventoryUpdateFailureCount > 0) {
            "OFR status changed to SHIPPED. Warning: $inventoryUpdateFailureCount inventory location updates failed."
        } else {
            "OFR status changed to SHIPPED. All inventory locations updated successfully."
        }

        return message
    }

    /**
     * Direct OFR Processing (Web-based Express Fulfillment)
     * Creates OFR and processes it immediately without task creation
     *
     * Flow:
     * 1. Generate OFR ID and GIN number
     * 2. Validate package barcodes are unique and not already used
     * 3. Build line items from packages
     * 4. Build packages with assigned items
     * 5. Move all items to IN_TRANSIT location
     * 6. Generate AWB if requested
     * 7. Build GIN notification with attachments
     * 8. Build loading documents
     * 9. Build complete OFR
     * 10. Save OFR with SHIPPED status
     * 11. Return response
     */
    @Transactional
    fun createDirectOfrAndProcess(request: CreateDirectOfrRequest, createdBy: String?, authToken: String): DirectOfrResponse {
        logger.info("Creating Direct OFR for account: {}", request.accountId)

        // Step 1: Generate IDs
        val fulfillmentId = sequenceGeneratorService.generateOfrId()
        val ginNumber = sequenceGeneratorService.generateGinNumber()
        logger.info("Generated fulfillment ID: {} and GIN: {}", fulfillmentId, ginNumber)

        // Step 2: Validate package barcodes are unique and not already used
        val duplicateBarcodes = mutableListOf<String>()
        val providedBarcodes = mutableSetOf<String>()

        request.packages.forEach { pkg ->
            val barcode = pkg.packageBarcode

            // Check for duplicates within the request
            if (!providedBarcodes.add(barcode)) {
                duplicateBarcodes.add(barcode)
                logger.warn("Duplicate package barcode in request: {}", barcode)
            }

            // Check if barcode already exists in database
            if (ofrRepository.existsByPackagesPackageBarcode(barcode)) {
                logger.error("Package barcode already exists in system: {}", barcode)
                throw InvalidOrderRequestException("Package barcode '$barcode' has already been used in another order")
            }
        }

        if (duplicateBarcodes.isNotEmpty()) {
            throw InvalidOrderRequestException("Duplicate package barcodes found in request: ${duplicateBarcodes.joinToString(", ")}")
        }

        // Step 3: Build LineItems from packages
        // Group items by SKU/itemBarcode to create line items
        val allItems = request.packages.flatMap { pkg ->
            pkg.items.map { item -> item }
        }

        val itemGroups = allItems.groupBy { item ->
            if (item.itemType == ItemType.SKU_ITEM && item.skuId != null) {
                "SKU-${item.skuId}"
            } else {
                "ITEM-${item.itemBarcode}"
            }
        }

        val lineItems = mutableListOf<LineItem>()
        itemGroups.forEach { (key, items) ->
            val lineItemId = sequenceGeneratorService.generateLineItemId()
            val firstItem = items.first()

            val lineItem = LineItem(
                lineItemId = lineItemId,
                itemType = firstItem.itemType,
                skuId = firstItem.skuId,
                itemBarcode = if (firstItem.itemType != ItemType.SKU_ITEM) firstItem.itemBarcode else null,
                quantityOrdered = items.size,
                quantityPicked = items.size,    // Already picked
                quantityShipped = items.size,   // Already shipped
                allocatedItems = items.map { item ->
                    AllocatedItem(
                        storageItemId = item.storageItemId,
                        location = "IN_TRANSIT",
                        picked = true,
                        pickedAt = LocalDateTime.now(),
                        pickedBy = createdBy
                    )
                }.toMutableList()
            )

            lineItems.add(lineItem)
        }

        // Step 4: Build Packages with AssignedItems
        val packages = request.packages.mapIndexed { index, pkgDto ->
            Package(
                packageId = sequenceGeneratorService.generatePackageId(),
                packageBarcode = pkgDto.packageBarcode,
                dimensions = PackageDimensions(
                    length = pkgDto.dimensions.length,
                    width = pkgDto.dimensions.width,
                    height = pkgDto.dimensions.height,
                    unit = pkgDto.dimensions.unit
                ),
                weight = PackageWeight(
                    value = pkgDto.weight.value,
                    unit = pkgDto.weight.unit
                ),
                assignedItems = pkgDto.items.map { item ->
                    AssignedItem(
                        storageItemId = item.storageItemId,
                        skuId = item.skuId,
                        itemType = item.itemType,
                        itemBarcode = item.itemBarcode
                    )
                }.toMutableList(),
                loadedOnTruck = true,
                truckNumber = request.truckNumber,
                loadedAt = LocalDateTime.now(),
                createdAt = LocalDateTime.now()
            )
        }.toMutableList()

        logger.info("Built {} line items and {} packages", lineItems.size, packages.size)

        // Step 5: Update ALL storage items to IN_TRANSIT
        logger.info("Updating {} storage items to IN_TRANSIT", allItems.size)
        var inventoryUpdateSuccessCount = 0
        var inventoryUpdateFailureCount = 0

        allItems.forEach { item ->
            try {
                inventoryServiceClient.changeStorageItemLocation(
                    itemBarcode = item.itemBarcode,
                    request = com.wmspro.order.client.ChangeLocationRequest(
                        newLocation = "IN_TRANSIT",
                        taskCode = fulfillmentId,
                        action = "DIRECT_SHIPPED",
                        notes = "Direct processing - item shipped immediately via web portal",
                        reason = "Express fulfillment without task execution"
                    ),
                    authToken = authToken
                )
                inventoryUpdateSuccessCount++
                logger.debug("Updated inventory location for item: {}", item.itemBarcode)
            } catch (e: Exception) {
                inventoryUpdateFailureCount++
                logger.error("Failed to update inventory location for ${item.itemBarcode}: ${e.message}", e)
            }
        }

        logger.info("Inventory updates: {} succeeded, {} failed", inventoryUpdateSuccessCount, inventoryUpdateFailureCount)

        // Step 6: Handle AWB generation if requested
        var awbDetails: AwbDetailsResponse? = null
        val shippingDetails = if (request.awbCondition == AwbCondition.CREATE_FOR_CUSTOMER) {
            logger.info("Generating AWB for direct OFR")
            try {
                // Create a temporary OFR for AWB generation
                val tempOfr = OrderFulfillmentRequest(
                    fulfillmentId = fulfillmentId,
                    accountId = request.accountId,
                    fulfillmentSource = request.fulfillmentSource,
                    executionApproach = ExecutionApproach.DIRECT_PROCESSING,
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
                        awbCondition = request.awbCondition,
                        carrier = request.shippingDetails?.carrier,
                        requestedServiceType = request.shippingDetails?.requestedServiceType
                    ),
                    packages = packages
                )
                ofrRepository.save(tempOfr)

                val awbConfig = createAwbForAllPackages(fulfillmentId)

                awbDetails = AwbDetailsResponse(
                    shipmentId = awbConfig.shipmentId,
                    awbNumber = awbConfig.awbNumber,
                    carrier = awbConfig.carrier,
                    trackingUrl = awbConfig.trackingUrl,
                    shippingLabelPdf = awbConfig.shippingLabelPdf,
                    awbPdf = awbConfig.awbPdf
                )

                ShippingDetails(
                    awbCondition = request.awbCondition,
                    carrier = awbConfig.carrier,
                    requestedServiceType = request.shippingDetails?.requestedServiceType,
                    shipmentId = awbConfig.shipmentId,
                    awbNumber = awbConfig.awbNumber,
                    awbPdf = awbConfig.awbPdf,
                    trackingUrl = awbConfig.trackingUrl,
                    shippingLabelPdf = awbConfig.shippingLabelPdf
                )
            } catch (e: Exception) {
                logger.error("AWB generation failed: ${e.message}", e)
                ShippingDetails(
                    awbCondition = request.awbCondition,
                    carrier = request.shippingDetails?.carrier,
                    requestedServiceType = request.shippingDetails?.requestedServiceType
                )
            }
        } else {
            ShippingDetails(
                awbCondition = request.awbCondition,
                carrier = request.shippingDetails?.carrier,
                requestedServiceType = request.shippingDetails?.requestedServiceType
            )
        }

        // Step 7: Build GIN notification with attachments
        val ginNotification = GinNotification(
            sentToCustomer = false,
            ginDate = request.ginDate,
            attachments = buildGinAttachments(request.loadingDocuments)
        )

        // Step 8: Build Loading Documents
        val loadingDocuments = request.loadingDocuments?.let {
            com.wmspro.order.model.LoadingDocuments(
                packagePhotosUrls = it.packagePhotosUrls,
                truckDriverPhotoUrl = it.truckDriverPhotoUrl,
                truckDriverIdProofUrl = it.truckDriverIdProofUrl
            )
        }

        // Step 9: Build complete OFR
        val ofr = OrderFulfillmentRequest(
            fulfillmentId = fulfillmentId,
            accountId = request.accountId,
            fulfillmentSource = request.fulfillmentSource,
            externalOrderId = request.externalOrderId,
            externalOrderNumber = request.externalOrderNumber,
            fulfillmentStatus = FulfillmentStatus.SHIPPED,
            priority = request.priority,
            executionApproach = ExecutionApproach.DIRECT_PROCESSING,

            // No task IDs - direct processing
            pickingTaskId = null,
            packMoveTaskId = null,
            pickPackMoveTaskId = null,
            loadingTaskId = null,

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
            lineItems = lineItems,
            packages = packages,
            shippingDetails = shippingDetails,
            orderValue = request.orderValue?.let {
                OrderValue(
                    subtotal = it.subtotal,
                    shipping = it.shipping,
                    tax = it.tax,
                    total = it.total,
                    currency = it.currency
                )
            },

            ginNumber = ginNumber,
            ginNotification = ginNotification,
            loadingDocuments = loadingDocuments,

            statusHistory = mutableListOf(
                StatusHistory(
                    status = FulfillmentStatus.RECEIVED,
                    timestamp = LocalDateTime.now(),
                    user = createdBy,
                    automated = true,
                    notes = "Direct processing initiated",
                    currentStatus = false
                ),
                StatusHistory(
                    status = FulfillmentStatus.SHIPPED,
                    timestamp = LocalDateTime.now(),
                    user = createdBy,
                    automated = true,
                    notes = "Direct processing - items shipped immediately via truck ${request.truckNumber}",
                    currentStatus = true
                )
            ),

            notes = request.notes,
            createdBy = createdBy,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )

        // Step 10: Save OFR
        val savedOfr = ofrRepository.save(ofr)
        logger.info("Direct OFR created successfully: {} with GIN: {}", savedOfr.fulfillmentId, savedOfr.ginNumber)

        // Step 11: Build and return response
        return DirectOfrResponse(
            fulfillmentId = savedOfr.fulfillmentId,
            ginNumber = savedOfr.ginNumber!!,
            fulfillmentStatus = savedOfr.fulfillmentStatus.name,
            accountId = savedOfr.accountId,
            totalPackages = savedOfr.packages.size,
            totalItems = savedOfr.lineItems.sumOf { it.quantityOrdered },
            truckNumber = request.truckNumber,
            awbGenerated = awbDetails != null,
            awbDetails = awbDetails,
            createdAt = savedOfr.createdAt,
            updatedAt = savedOfr.updatedAt
        )
    }

    /**
     * Helper: Build GIN attachments from loading documents
     */
    private fun buildGinAttachments(loadingDocs: LoadingDocumentsDto?): List<GinAttachment> {
        if (loadingDocs == null) return emptyList()

        val attachments = mutableListOf<GinAttachment>()

        // Add signed GIN
        loadingDocs.signedGinUrl?.let {
            attachments.add(GinAttachment(fileName = "Signed_GIN.pdf", fileUrl = it))
        }

        // Add package photos
        loadingDocs.packagePhotosUrls.forEachIndexed { index, url ->
            attachments.add(GinAttachment(fileName = "Package_Photo_${index + 1}.jpg", fileUrl = url))
        }

        // Add driver documents
        loadingDocs.truckDriverPhotoUrl?.let {
            attachments.add(GinAttachment(fileName = "Driver_Photo.jpg", fileUrl = it))
        }
        loadingDocs.truckDriverIdProofUrl?.let {
            attachments.add(GinAttachment(fileName = "Driver_ID_Proof.pdf", fileUrl = it))
        }

        return attachments
    }
}
