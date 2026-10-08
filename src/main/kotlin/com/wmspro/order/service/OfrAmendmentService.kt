package com.wmspro.order.service

import com.wmspro.common.billing.BillingSourceClaimState
import com.wmspro.order.dto.*
import com.wmspro.order.enums.ExecutionApproach
import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.enums.FulfillmentType
import com.wmspro.order.enums.ItemType
import com.wmspro.order.exception.InvalidOrderRequestException
import com.wmspro.order.model.*
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Adds items to an outbound order after its GIN was created, so staff who create the GIN first and
 * pick afterwards do not need a second GIN for items found later.
 *
 * Allowed only while the GIN has not been sent to the customer: once sent, the customer holds a
 * document that would no longer match. Also refused once the order is billed - its movement has
 * been invoiced - and for task-based orders, which are amended through their tasks.
 *
 * The addition runs through the same steps as creating that kind of order, so stock, transactions,
 * package barcodes and the pick list come out exactly as if the items had been on it from the start.
 */
@Service
class OfrAmendmentService(
    private val ofrRepository: OrderFulfillmentRequestRepository,
    private val orderFulfillmentService: OrderFulfillmentService,
    private val locationQuantityOutboundService: LocationQuantityOutboundService,
    private val containerQuantityOutboundService: ContainerQuantityOutboundService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun addBarcodedItems(fulfillmentId: String, request: AddDirectItemsRequest, user: String?, authToken: String): AddOfrItemsResponse {
        val ofr = loadAmendable(fulfillmentId, FulfillmentType.STANDARD_TASK_BASED)
        val items = request.packages.flatMap { it.items }

        val alreadyOnOrder = ofr.lineItems.flatMap { line -> line.allocatedItems.map { it.storageItemId } }.toSet()
        val repeated = items.filter { it.storageItemId in alreadyOnOrder }.map { it.itemBarcode }
        if (repeated.isNotEmpty()) {
            throw InvalidOrderRequestException("Already on this GIN: ${repeated.joinToString(", ")}")
        }
        val duplicates = items.groupingBy { it.storageItemId }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw InvalidOrderRequestException("The same item appears more than once in the request")
        }
        orderFulfillmentService.validateNewPackageBarcodes(request.packages.map { it.packageBarcode })

        // Ship first, and add only what actually shipped: an item Inventory refuses (already
        // shipped, unknown barcode) must not appear on the customer's GIN.
        val shipment = orderFulfillmentService.shipDirectItems(items, fulfillmentId, authToken)
        val skipped = shipment.failedBarcodes.toSet()
        val packages = request.packages
            .map { pkg -> pkg.copy(items = pkg.items.filter { it.itemBarcode !in skipped }) }
            .filter { it.items.isNotEmpty() }
        if (packages.isEmpty()) {
            throw InvalidOrderRequestException("None of the items could be shipped: ${skipped.joinToString(", ")}")
        }
        val shipped = packages.flatMap { it.items }

        val lines = orderFulfillmentService.buildDirectLineItems(shipped, user)
        orderFulfillmentService.applyPickupLocations(lines, shipment.pickedFrom)
        val addition = OfrAddition(
            lineItems = lines,
            packages = orderFulfillmentService.buildDirectPackages(packages, ofr.packages.firstOrNull()?.truckNumber),
            itemSources = emptyList(),
            itemCount = shipped.size
        )
        val saved = save(ofr, addition, user)

        try {
            orderFulfillmentService.consumePackageBarcodesForDirectOFR(packages.map { it.packageBarcode }, authToken)
        } catch (e: Exception) {
            // Same as creation: the order stands even if barcode bookkeeping fails.
            logger.error("Failed to consume package barcodes added to {}: {}", fulfillmentId, e.message, e)
        }
        return response(saved, addition, skipped.toList())
    }

    fun addLocationQuantityItems(
        fulfillmentId: String,
        request: AddLocationQuantityItemsRequest,
        user: String,
        authToken: String?
    ): AddOfrItemsResponse {
        val ofr = loadAmendable(fulfillmentId, FulfillmentType.LOCATION_QUANTITY_BASED)
        val addition = locationQuantityOutboundService.prepareAddition(ofr, request.itemsPicked, request.packages, user, authToken)
        return response(save(ofr, addition, user), addition)
    }

    fun addContainerQuantityItems(
        fulfillmentId: String,
        request: AddContainerQuantityItemsRequest,
        user: String,
        authToken: String?
    ): AddOfrItemsResponse {
        val ofr = loadAmendable(fulfillmentId, FulfillmentType.CONTAINER_QUANTITY_BASED)
        val addition = containerQuantityOutboundService.prepareAddition(ofr, request.itemsPicked, request.packages, user, authToken)
        return response(save(ofr, addition, user), addition)
    }

    private fun loadAmendable(fulfillmentId: String, expectedType: FulfillmentType): OrderFulfillmentRequest {
        val ofr = ofrRepository.findByFulfillmentId(fulfillmentId)
            .orElseThrow { NoSuchElementException("Outbound order $fulfillmentId not found") }
        refusalReason(ofr)?.let { throw IllegalStateException(it) }
        if (ofr.fulfillmentType != expectedType) {
            throw InvalidOrderRequestException(
                "Outbound order $fulfillmentId was created as ${ofr.fulfillmentType}; add items of that kind instead"
            )
        }
        return ofr
    }

    private fun save(loaded: OrderFulfillmentRequest, addition: OfrAddition, user: String?): OrderFulfillmentRequest {
        val now = LocalDateTime.now()
        // Shipping the items takes a call per item, so merge onto the order as it is now: a GIN
        // sent or a document attached meanwhile must not be overwritten by the copy read earlier.
        val ofr = ofrRepository.findByFulfillmentId(loaded.fulfillmentId).orElse(loaded)
        if (ofr.ginNotification?.sentToCustomer == true) {
            logger.warn("GIN {} was sent while items were being added to {}; recording them anyway", ofr.ginNumber, ofr.fulfillmentId)
        }
        val lines = ofr.lineItems.toMutableList()
        for (added in addition.lineItems) {
            // A SKU already on the GIN gains the new units on its existing line, as one line per SKU.
            val sameSku = lines.indexOfFirst {
                added.itemType == ItemType.SKU_ITEM && it.itemType == ItemType.SKU_ITEM &&
                    it.skuId != null && it.skuId == added.skuId &&
                    it.quantityInventoryReferences.isEmpty() && added.quantityInventoryReferences.isEmpty()
            }
            if (sameSku >= 0) {
                val existing = lines[sameSku]
                lines[sameSku] = existing.copy(
                    quantityOrdered = existing.quantityOrdered + added.quantityOrdered,
                    quantityPicked = existing.quantityPicked + added.quantityPicked,
                    quantityShipped = existing.quantityShipped + added.quantityShipped,
                    allocatedItems = (existing.allocatedItems + added.allocatedItems).toMutableList()
                )
            } else {
                lines += added
            }
        }

        val history = ofr.statusHistory.map { it.copy(currentStatus = false) }.toMutableList()
        history += StatusHistory(
            status = ofr.fulfillmentStatus,
            timestamp = now,
            user = user,
            notes = "Added ${addition.itemCount} item(s) in ${addition.packages.size} package(s) before the GIN was sent",
            currentStatus = true
        )

        val saved = ofrRepository.save(
            ofr.copy(
                lineItems = lines,
                packages = (ofr.packages + addition.packages).toMutableList(),
                quantitySourceDetails = if (addition.itemSources.isEmpty()) ofr.quantitySourceDetails else QuantitySourceDetails(
                    itemSources = ofr.quantitySourceDetails?.itemSources.orEmpty() + addition.itemSources
                ),
                statusHistory = history,
                updatedBy = user,
                updatedAt = now
            )
        )
        logger.info(
            "Added {} item(s) in {} package(s) to {} (GIN {}) by {}",
            addition.itemCount, addition.packages.size, saved.fulfillmentId, saved.ginNumber, user
        )
        return saved
    }

    private fun response(saved: OrderFulfillmentRequest, addition: OfrAddition, skipped: List<String> = emptyList()) =
        AddOfrItemsResponse(
            fulfillmentId = saved.fulfillmentId,
            ginNumber = requireNotNull(saved.ginNumber),
            addedItems = addition.itemCount,
            addedPackages = addition.packages.size,
            skippedBarcodes = skipped,
            totalItems = saved.lineItems.sumOf { it.quantityOrdered },
            totalPackages = saved.packages.size
        )

    companion object {
        /**
         * Why items cannot be added to this order, or null when they can. Shared with the order
         * detail response so the screen only offers "Add items" when it would succeed.
         */
        fun refusalReason(ofr: OrderFulfillmentRequest): String? = when {
            ofr.ginNumber == null -> "This outbound order has no GIN yet"
            ofr.ginNotification?.sentToCustomer == true ->
                "The GIN has already been sent to the customer, so items can no longer be added. Create a new GIN for them."
            ofr.billingInvoiceId != null ||
                (ofr.billingClaim != null && ofr.billingClaim.state != BillingSourceClaimState.RELEASED) ->
                "This outbound order has already been billed, so items can no longer be added"
            ofr.executionApproach != ExecutionApproach.DIRECT_PROCESSING ->
                "Items can only be added to orders processed directly; task-based orders are changed through their tasks"
            ofr.fulfillmentStatus == FulfillmentStatus.CANCELLED -> "This outbound order is cancelled"
            else -> null
        }
    }
}

/** Lines, packages and quantity sources built for items added to an existing order. */
data class OfrAddition(
    val lineItems: List<LineItem>,
    val packages: List<Package>,
    val itemSources: List<ItemSource>,
    val itemCount: Int
)

internal fun OrderFulfillmentRequest.customerInfoDto() = CustomerInfoDto(
    name = customerInfo.name,
    email = customerInfo.email,
    phone = customerInfo.phone
)

internal fun OrderFulfillmentRequest.shippingAddressDto() = ShippingAddressDto(
    name = shippingAddress.name,
    company = shippingAddress.company,
    addressLine1 = shippingAddress.addressLine1,
    addressLine2 = shippingAddress.addressLine2,
    city = shippingAddress.city,
    state = shippingAddress.state,
    country = shippingAddress.country,
    postalCode = shippingAddress.postalCode,
    phone = shippingAddress.phone
)
