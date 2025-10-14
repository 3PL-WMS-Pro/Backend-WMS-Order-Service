package com.wmspro.order.service

import com.wmspro.common.service.AccountService
import com.wmspro.common.service.UserService
import com.wmspro.order.client.ProductServiceClient
import com.wmspro.order.client.TaskServiceClient
import com.wmspro.order.dto.*
import com.wmspro.order.enums.ExecutionApproach
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
class OrderFulfillmentDetailsService(
    private val ofrRepository: OrderFulfillmentRequestRepository,
    private val taskServiceClient: TaskServiceClient,
    private val productServiceClient: ProductServiceClient,
    private val accountService: AccountService,
    private val userService: UserService
) {

    private val logger = LoggerFactory.getLogger(OrderFulfillmentDetailsService::class.java)

    /**
     * Helper function to fetch SKU details from Product Service
     * Returns a map of skuId -> SKUDetails
     */
    private data class SKUDetails(
        val skuCode: String?,
        val productTitle: String?,
        val primaryImageUrl: String?
    )

    private fun fetchSKUDetails(skuIds: List<Long>): Map<Long, SKUDetails> {
        if (skuIds.isEmpty()) return emptyMap()

        logger.debug("Fetching SKU details for ${skuIds.size} SKU IDs")

        return try {
            val request = BatchSkuRequest(
                skuIds = skuIds,
                fields = listOf("skuId", "skuCode", "productTitle", "images")
            )

            val response = productServiceClient.getBatchSkuDetails(request)

            if (!response.success || response.data == null) {
                logger.error("Product service returned error: ${response.message}")
                return skuIds.associateWith {
                    SKUDetails(
                        skuCode = "N/A (ERR)",
                        productTitle = "N/A (ERR)",
                        primaryImageUrl = null
                    )
                }
            }

            // Assign to local variable to enable smart cast
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

                    resultMap[skuId] = SKUDetails(
                        skuCode = skuCode,
                        productTitle = productTitle,
                        primaryImageUrl = primaryImageUrl
                    )
                }
            }

            skuIds.forEach { skuId ->
                if (!resultMap.containsKey(skuId)) {
                    logger.warn("SKU details not found for SKU ID: $skuId")
                    resultMap[skuId] = SKUDetails(
                        skuCode = "N/A (ERR)",
                        productTitle = "N/A (ERR)",
                        primaryImageUrl = null
                    )
                }
            }

            logger.info("Successfully fetched SKU details for ${resultMap.size}/${skuIds.size} SKUs")
            resultMap

        } catch (e: Exception) {
            logger.error("Failed to fetch SKU details from product service", e)
            skuIds.associateWith {
                SKUDetails(
                    skuCode = "N/A (ERR)",
                    productTitle = "N/A (ERR)",
                    primaryImageUrl = null
                )
            }
        }
    }

    /**
     * API 108: Get OFR Header
     * Common header information displayed across all tabs
     */
    fun getOfrHeader(ofrId: String, authToken: String): OfrHeaderResponse {
        logger.info("Getting header for OFR: $ofrId")

        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        // Fetch account name
        val accountName = if (authToken.isNotEmpty()) {
            accountService.fetchAccountNames(listOf(ofr.accountId), authToken)[ofr.accountId.toString()]
        } else null

        return OfrHeaderResponse(
            fulfillmentId = ofr.fulfillmentId,
            notificationSource = ofr.fulfillmentSource.name,
            customer = accountName,
            ginNumber = ofr.ginNumber,
            createdDate = ofr.createdAt
        )
    }

    /**
     * API 109: Get Picking Details Tab
     * Shows picking task information and items to pick
     */
    fun getPickingDetailsTab(ofrId: String, authToken: String): PickingDetailsTabResponse {
        logger.info("Getting picking details tab for OFR: $ofrId")

        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        // Validate execution approach
        if (ofr.executionApproach == ExecutionApproach.PICK_PACK_MOVE_TOGETHER) {
            throw IllegalArgumentException("Picking Details tab not applicable for combined execution approach. Use Pick Pack Move Details tab.")
        }

        val taskCode = ofr.pickingTaskId
            ?: throw IllegalArgumentException("No picking task found for this OFR")

        val taskDetails = taskServiceClient.getTaskWebDetails(taskCode, ofr.accountId).data
            ?: throw IllegalArgumentException("Task details not found for task: $taskCode")

        val pickingDetails = taskDetails.pickingDetails
            ?: throw IllegalArgumentException("Picking details not found in task")

        // Fetch user full name
        val executiveName = taskDetails.assignedTo?.let { email ->
            userService.fetchUserFullNames(listOf(email), authToken)[email]
        }

        // Calculate locations count from distinct pickedFromLocation
        val locationsCount = pickingDetails.itemsToPick
            .flatMap { it.itemsPicked ?: emptyList() }
            .map { it.pickedFromLocation }
            .distinct()
            .size

        // Fetch SKU details for enrichment
        val skuIds = pickingDetails.itemsToPick.mapNotNull { it.skuId }
        val skuDetailsMap = fetchSKUDetails(skuIds)

        // Build items list
        val items = pickingDetails.itemsToPick.mapIndexed { index, item ->
            val skuDetails = item.skuId?.let { skuDetailsMap[it] }

            // Determine itemId, itemName based on itemType
            val (itemId, itemName) = when (item.itemType) {
                "SKU_ITEM" -> Pair(skuDetails?.skuCode ?: "N/A", skuDetails?.productTitle)
                "BOX", "PALLET" -> Pair(item.itemBarcode ?: "N/A", item.itemType)
                else -> Pair("N/A", null)
            }

            // Get picking location from first picked item (or "N/A" if not picked yet)
            val pickingLocation = item.itemsPicked?.firstOrNull()?.pickedFromLocation ?: "N/A"

            PickingItemDetail(
                serialNumber = index + 1,
                itemId = itemId,
                itemName = itemName,
                itemImage = skuDetails?.primaryImageUrl,
                itemBarcode = item.itemBarcode,
                skuId = item.skuId,
                pickingLocation = pickingLocation
            )
        }

        return PickingDetailsTabResponse(
            taskInfo = PickingTaskInfo(
                taskCode = taskDetails.taskCode,
                createdOn = taskDetails.createdAt,
                executiveName = executiveName,
                items = pickingDetails.itemsToPick.size,
                locations = locationsCount
            ),
            items = items
        )
    }

    /**
     * API 110: Get Pack Move Pending Tab
     * Shows pack move task with items and their packing zones
     */
    fun getPackMovePendingTab(ofrId: String, authToken: String): PackMovePendingTabResponse {
        logger.info("Getting pack move pending tab for OFR: $ofrId")

        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        // Validate execution approach
        if (ofr.executionApproach == ExecutionApproach.PICK_PACK_MOVE_TOGETHER) {
            throw IllegalArgumentException("Pack Move Pending tab not applicable for combined execution approach. Use Pick Pack Move Details tab.")
        }

        val packMoveTaskCode = ofr.packMoveTaskId
            ?: throw IllegalArgumentException("No pack move task found for this OFR")

        val packMoveTask = taskServiceClient.getTaskWebDetails(packMoveTaskCode, ofr.accountId).data
            ?: throw IllegalArgumentException("Task details not found for task: $packMoveTaskCode")

        val packMoveDetails = packMoveTask.packMoveDetails
            ?: throw IllegalArgumentException("Pack move details not found in task")

        // Need to fetch picking task to get packing zone information
        val pickingTaskCode = ofr.pickingTaskId
            ?: throw IllegalArgumentException("No picking task found for this OFR")

        val pickingTask = taskServiceClient.getTaskWebDetails(pickingTaskCode, ofr.accountId).data
            ?: throw IllegalArgumentException("Picking task details not found for task: $pickingTaskCode")

        val pickingDetails = pickingTask.pickingDetails
            ?: throw IllegalArgumentException("Picking details not found in picking task")

        // Fetch user full name
        val executiveName = packMoveTask.assignedTo?.let { email ->
            userService.fetchUserFullNames(listOf(email), authToken)[email]
        }

        // Calculate packing zones count from picking task
        val packingZonesCount = pickingDetails.packingZoneVisits
            ?.map { it.packingZone }
            ?.distinct()
            ?.size ?: 0

        // Create a map of storageItemId -> packingZone from picking task
        val itemToPackingZoneMap = mutableMapOf<Long, String>()
        pickingDetails.packingZoneVisits?.forEach { visit ->
            visit.itemsDropped.forEach { droppedItem ->
                droppedItem.storageItemId?.let {
                    itemToPackingZoneMap[it] = visit.packingZone
                }
            }
        }

        // Fetch SKU details for enrichment
        val skuIds = packMoveDetails.itemsToVerify.mapNotNull { it.skuId }
        val skuDetailsMap = fetchSKUDetails(skuIds)

        // Build items list
        val items = packMoveDetails.itemsToVerify.mapIndexed { index, item ->
            val skuDetails = item.skuId?.let { skuDetailsMap[it] }

            // Determine itemId, itemName based on itemType
            val (itemId, itemName) = when (item.itemType) {
                "SKU_ITEM" -> Pair(skuDetails?.skuCode ?: "N/A", skuDetails?.productTitle)
                "BOX", "PALLET" -> Pair(item.itemBarcode, item.itemType)
                else -> Pair("N/A", null)
            }

            // Get packing zone from map
            val packingZone = itemToPackingZoneMap[item.storageItemId] ?: "N/A"

            PackMoveItemDetail(
                serialNumber = index + 1,
                itemId = itemId,
                itemName = itemName,
                itemImage = skuDetails?.primaryImageUrl,
                itemBarcode = item.itemBarcode,
                skuId = item.skuId,
                packingZone = packingZone
            )
        }

        return PackMovePendingTabResponse(
            taskInfo = PackMoveTaskInfo(
                taskCode = packMoveTask.taskCode,
                createdOn = packMoveTask.createdAt,
                executiveName = executiveName,
                items = packMoveDetails.itemsToVerify.size,
                packingZones = packingZonesCount
            ),
            items = items
        )
    }

    /**
     * API 111: Get Pick Pack Move Details Tab
     * Shows pick pack move task information with items
     */
    fun getPickPackMoveDetailsTab(ofrId: String, authToken: String): PickPackMoveDetailsTabResponse {
        logger.info("Getting pick pack move details tab for OFR: $ofrId")

        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        // Validate execution approach
        if (ofr.executionApproach == ExecutionApproach.SEPARATED_PICKING) {
            throw IllegalArgumentException("Pick Pack Move Details tab not applicable for separate execution approach. Use Picking Details and Pack Move Pending tabs.")
        }

        val taskCode = ofr.pickPackMoveTaskId
            ?: throw IllegalArgumentException("No pick pack move task found for this OFR")

        val taskDetails = taskServiceClient.getTaskWebDetails(taskCode, ofr.accountId).data
            ?: throw IllegalArgumentException("Task details not found for task: $taskCode")

        val pickPackMoveDetails = taskDetails.pickPackMoveDetails
            ?: throw IllegalArgumentException("Pick pack move details not found in task")

        // Fetch user full name
        val executiveName = taskDetails.assignedTo?.let { email ->
            userService.fetchUserFullNames(listOf(email), authToken)[email]
        }

        // Calculate duration
        val durationMinutes = if (taskDetails.timeSpentMinutes != null) {
            taskDetails.timeSpentMinutes
        } else if (taskDetails.actualStartTime != null) {
            val endTime = taskDetails.actualEndTime ?: LocalDateTime.now()
            ChronoUnit.MINUTES.between(taskDetails.actualStartTime, endTime).toInt()
        } else {
            null
        }

        // Calculate locations count from distinct pickup locations
        val locationsCount = pickPackMoveDetails.itemsToPick
            .flatMap { it.pickupLocations }
            .map { it.locationCode }
            .distinct()
            .size

        // Fetch SKU details for enrichment
        val skuIds = pickPackMoveDetails.itemsToPick.mapNotNull { it.skuId }
        val skuDetailsMap = fetchSKUDetails(skuIds)

        // Build items list
        // TODO: This implementation will need to change after PickPackMove task model is updated
        val items = pickPackMoveDetails.itemsToPick.mapIndexed { index, item ->
            val skuDetails = item.skuId?.let { skuDetailsMap[it] }

            // Determine itemId, itemName based on itemType
            val (itemId, itemName) = when (item.itemType) {
                "SKU_ITEM" -> Pair(skuDetails?.skuCode ?: "N/A", skuDetails?.productTitle)
                "BOX", "PALLET" -> Pair(item.itemBarcode ?: "N/A", item.itemType)
                else -> Pair("N/A", null)
            }

            // Get first pickup location
            val pickingLocation = item.pickupLocations.firstOrNull()?.locationCode

            PickPackMoveItemDetail(
                serialNumber = index + 1,
                itemId = itemId,
                itemName = itemName,
                itemImage = skuDetails?.primaryImageUrl,
                itemBarcode = item.itemBarcode,
                skuId = item.skuId,
                pickingLocation = pickingLocation
            )
        }

        return PickPackMoveDetailsTabResponse(
            taskInfo = PickPackMoveTaskInfo(
                taskCode = taskDetails.taskCode,
                startedAt = taskDetails.actualStartTime,
                completedAt = taskDetails.actualEndTime,
                durationMinutes = durationMinutes,
                executiveName = executiveName,
                items = pickPackMoveDetails.itemsToPick.size,
                locations = locationsCount
            ),
            items = items
        )
    }

    /**
     * API 112: Get Ready To Dispatch Tab
     * Shows AWB details and packages with items
     */
    fun getReadyToDispatchTab(ofrId: String, authToken: String): ReadyToDispatchTabResponse {
        logger.info("Getting ready to dispatch tab for OFR: $ofrId")

        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        // Determine which task to fetch based on execution approach
        val taskCode = when (ofr.executionApproach) {
            ExecutionApproach.PICK_PACK_MOVE_TOGETHER -> ofr.pickPackMoveTaskId
            ExecutionApproach.SEPARATED_PICKING -> ofr.packMoveTaskId
        } ?: throw IllegalArgumentException("No task found for this OFR")

        val taskDetails = taskServiceClient.getTaskWebDetails(taskCode, ofr.accountId).data
            ?: throw IllegalArgumentException("Task details not found for task: $taskCode")

        // Fetch user full name
        val executiveName = taskDetails.assignedTo?.let { email ->
            userService.fetchUserFullNames(listOf(email), authToken)[email]
        }

        // Build package details
        val packageDetails = ofr.packages.mapIndexed { index, pkg ->
            // Format dimension string
            val dimensionStr = pkg.dimensions?.let {
                "${it.length.toInt()}×${it.width.toInt()}×${it.height.toInt()}"
            }

            // Format weight string
            val weightStr = pkg.weight?.let {
                "${it.value}${it.unit.uppercase()}"
            }

            // Fetch SKU details for items in package
            val skuIdsInPackage = pkg.assignedItems.mapNotNull { it.skuId }
            val skuDetailsMap = fetchSKUDetails(skuIdsInPackage)

            // Build assigned items list
            val assignedItems = pkg.assignedItems.mapIndexed { itemIndex, assignedItem ->
                val skuDetails = assignedItem.skuId?.let { skuDetailsMap[it] }

                // Determine itemId, itemName based on itemType
                val (itemId, itemName) = when (assignedItem.itemType.name) {
                    "SKU_ITEM" -> Pair(skuDetails?.skuCode ?: "N/A", skuDetails?.productTitle)
                    "BOX", "PALLET" -> Pair(
                        assignedItem.itemBarcodes.firstOrNull() ?: "N/A",
                        assignedItem.itemType.name
                    )
                    else -> Pair("N/A", null)
                }

                PackageItemDetail(
                    serialNumber = itemIndex + 1,
                    itemId = itemId,
                    itemName = itemName,
                    itemImage = skuDetails?.primaryImageUrl,
                    itemBarcode = assignedItem.itemBarcodes.firstOrNull(),
                    skuId = assignedItem.skuId
                )
            }

            PackageDetail(
                serialNumber = index + 1,
                packageBarCode = pkg.packageBarcode,
                dimension = dimensionStr,
                weight = weightStr,
                items = pkg.assignedItems.size,
                dispatchArea = pkg.dispatchArea,
                assignedItems = assignedItems
            )
        }

        return ReadyToDispatchTabResponse(
            taskInfo = ReadyToDispatchInfo(
                taskCode = taskDetails.taskCode,
                executiveName = executiveName,
                awbPrinted = ofr.shippingDetails.awbPdf != null,
                awbNumber = ofr.shippingDetails.awbNumber,
                packages = ofr.packages.size
            ),
            packages = packageDetails
        )
    }

    /**
     * API 113: Get Loading Done & GIN Pending Tab
     * Shows loading task with truck details and packages
     */
    fun getLoadingTab(ofrId: String, authToken: String): LoadingTabResponse {
        logger.info("Getting loading tab for OFR: $ofrId")

        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        val taskCode = ofr.loadingTaskId
            ?: throw IllegalArgumentException("No loading task found for this OFR")

        val taskDetails = taskServiceClient.getTaskWebDetails(taskCode, ofr.accountId).data
            ?: throw IllegalArgumentException("Task details not found for task: $taskCode")

        val loadingDetails = taskDetails.loadingDetails
            ?: throw IllegalArgumentException("Loading details not found in task")

        // Fetch user full name
        val executiveName = taskDetails.assignedTo?.let { email ->
            userService.fetchUserFullNames(listOf(email), authToken)[email]
        }

        // Find the company card for this OFR's account
        val companyCard = loadingDetails.companyCards.firstOrNull { it.accountId == ofr.accountId }
            ?: throw IllegalArgumentException("Company card not found for account: ${ofr.accountId}")

        // Build package details (only for this OFR)
        val packageDetails = ofr.packages.mapIndexed { index, pkg ->
            // Format dimension string
            val dimensionStr = pkg.dimensions?.let {
                "${it.length.toInt()}×${it.width.toInt()}×${it.height.toInt()}"
            }

            // Format weight string
            val weightStr = pkg.weight?.let {
                "${it.value}${it.unit.uppercase()}"
            }

            // Fetch SKU details for items in package
            val skuIdsInPackage = pkg.assignedItems.mapNotNull { it.skuId }
            val skuDetailsMap = fetchSKUDetails(skuIdsInPackage)

            // Build assigned items list
            val assignedItems = pkg.assignedItems.mapIndexed { itemIndex, assignedItem ->
                val skuDetails = assignedItem.skuId?.let { skuDetailsMap[it] }

                // Determine itemId, itemName based on itemType
                val (itemId, itemName) = when (assignedItem.itemType.name) {
                    "SKU_ITEM" -> Pair(skuDetails?.skuCode ?: "N/A", skuDetails?.productTitle)
                    "BOX", "PALLET" -> Pair(
                        assignedItem.itemBarcodes.firstOrNull() ?: "N/A",
                        assignedItem.itemType.name
                    )
                    else -> Pair("N/A", null)
                }

                PackageItemDetail(
                    serialNumber = itemIndex + 1,
                    itemId = itemId,
                    itemName = itemName,
                    itemImage = skuDetails?.primaryImageUrl,
                    itemBarcode = assignedItem.itemBarcodes.firstOrNull(),
                    skuId = assignedItem.skuId
                )
            }

            PackageDetail(
                serialNumber = index + 1,
                packageBarCode = pkg.packageBarcode,
                dimension = dimensionStr,
                weight = weightStr,
                items = pkg.assignedItems.size,
                dispatchArea = pkg.dispatchArea,
                assignedItems = assignedItems
            )
        }

        return LoadingTabResponse(
            taskInfo = LoadingTaskInfo(
                taskCode = taskDetails.taskCode,
                createdOn = taskDetails.createdAt,
                executiveName = executiveName
            ),
            truckDetails = TruckDetails(
                vehicleNumber = loadingDetails.truckInfo?.truckNumber,
                signedGin = companyCard.signedGinDocument,
                driverPhotoProof = loadingDetails.truckInfo?.driverPhoto,
                driverIdentityProof = loadingDetails.truckInfo?.driverIdentityProof,
                placementPhotos = companyCard.packagePhotos
            ),
            packages = packageDetails
        )
    }

    /**
     * API 114: Get GIN Sent Tab
     * Shows GIN notification details with packages and attachments
     */
    fun getGinSentTab(ofrId: String, authToken: String): GinSentTabResponse {
        logger.info("Getting GIN sent tab for OFR: $ofrId")

        val ofr = ofrRepository.findById(ofrId).orElseThrow {
            IllegalArgumentException("Order Fulfillment Request not found: $ofrId")
        }

        val ginNotification = ofr.ginNotification
            ?: throw IllegalArgumentException("GIN notification not found for this OFR")

        // Calculate total items across all packages
        val totalItems = ofr.packages.sumOf { it.assignedItems.size }

        // Determine email status
        val emailStatus = if (ginNotification.sentToCustomer) {
            "Success"
        } else if (ginNotification.sentAt != null) {
            "Failed"
        } else {
            "Pending"
        }

        // Build package details (reuse same structure as Ready To Dispatch)
        val packageDetails = ofr.packages.mapIndexed { index, pkg ->
            // Format dimension string
            val dimensionStr = pkg.dimensions?.let {
                "${it.length.toInt()}×${it.width.toInt()}×${it.height.toInt()}"
            }

            // Format weight string
            val weightStr = pkg.weight?.let {
                "${it.value}${it.unit.uppercase()}"
            }

            // Fetch SKU details for items in package
            val skuIdsInPackage = pkg.assignedItems.mapNotNull { it.skuId }
            val skuDetailsMap = fetchSKUDetails(skuIdsInPackage)

            // Build assigned items list
            val assignedItems = pkg.assignedItems.mapIndexed { itemIndex, assignedItem ->
                val skuDetails = assignedItem.skuId?.let { skuDetailsMap[it] }

                // Determine itemId, itemName based on itemType
                val (itemId, itemName) = when (assignedItem.itemType.name) {
                    "SKU_ITEM" -> Pair(skuDetails?.skuCode ?: "N/A", skuDetails?.productTitle)
                    "BOX", "PALLET" -> Pair(
                        assignedItem.itemBarcodes.firstOrNull() ?: "N/A",
                        assignedItem.itemType.name
                    )
                    else -> Pair("N/A", null)
                }

                PackageItemDetail(
                    serialNumber = itemIndex + 1,
                    itemId = itemId,
                    itemName = itemName,
                    itemImage = skuDetails?.primaryImageUrl,
                    itemBarcode = assignedItem.itemBarcodes.firstOrNull(),
                    skuId = assignedItem.skuId
                )
            }

            PackageDetail(
                serialNumber = index + 1,
                packageBarCode = pkg.packageBarcode,
                dimension = dimensionStr,
                weight = weightStr,
                items = pkg.assignedItems.size,
                dispatchArea = pkg.dispatchArea,
                assignedItems = assignedItems
            )
        }

        return GinSentTabResponse(
            ginInfo = GinInfo(
                completedAt = ginNotification.sentAt,
                ginNumber = ofr.ginNumber,
                totalItems = totalItems,
                emailStatus = emailStatus
            ),
            ginForm = GinFormDetails(
                ginDate = ginNotification.ginDate,
                toEmail = ginNotification.toEmail,
                ccEmails = ginNotification.ccEmails,
                subject = ginNotification.subject,
                emailContent = ginNotification.emailContent
            ),
            packageDetails = packageDetails,
            attachments = ginNotification.attachments.map {
                GinAttachmentDto(
                    fileName = it.fileName,
                    fileUrl = it.fileUrl
                )
            }
        )
    }
}
