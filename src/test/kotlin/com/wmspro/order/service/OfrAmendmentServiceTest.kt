package com.wmspro.order.service

import com.wmspro.common.billing.BillingSourceClaim
import com.wmspro.common.billing.BillingSourceClaimState
import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.jwt.JwtTokenExtractor
import com.wmspro.common.service.AccountService
import com.wmspro.order.client.*
import com.wmspro.order.dto.*
import com.wmspro.order.enums.ExecutionApproach
import com.wmspro.order.enums.FulfillmentSource
import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.enums.FulfillmentType
import com.wmspro.order.enums.ItemType
import com.wmspro.order.exception.InvalidOrderRequestException
import com.wmspro.order.model.*
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional

/**
 * Infinity create the GIN first and pick afterwards, so items found later used to need a second
 * GIN. Items can now be added to the existing one - but only until the GIN is sent to the
 * customer, and never once the order is billed.
 */
class OfrAmendmentServiceTest {

    private lateinit var ofrRepository: OrderFulfillmentRequestRepository
    private lateinit var inventoryClient: InventoryServiceClient
    private lateinit var service: OfrAmendmentService
    private val authToken = "Bearer test-token"
    private var sequence = 100

    @BeforeEach
    fun setUp() {
        ofrRepository = mock(OrderFulfillmentRequestRepository::class.java)
        inventoryClient = mock(InventoryServiceClient::class.java)
        val sequences = mock(SequenceGeneratorService::class.java)
        `when`(sequences.generateLineItemId()).thenAnswer { "LI-${sequence++}" }
        `when`(sequences.generatePackageId()).thenAnswer { "PKG-${sequence++}" }
        `when`(ofrRepository.save(any(OrderFulfillmentRequest::class.java))).thenAnswer { it.arguments[0] }

        val fulfillment = OrderFulfillmentService(
            ofrRepository, sequences, mock(ProductServiceClient::class.java), inventoryClient,
            mock(TaskServiceClient::class.java), mock(JwtTokenExtractor::class.java), mock(AccountService::class.java)
        )
        service = OfrAmendmentService(
            ofrRepository, fulfillment,
            mock(LocationQuantityOutboundService::class.java), mock(ContainerQuantityOutboundService::class.java)
        )
    }

    @Test
    fun `items join the pending GIN with the bin they were picked from`() {
        stubOrder(order())
        ships("ITEM-2", 2L, from = "WH1-B-2-1-B")
        ships("ITEM-3", 3L, from = "WH2-A-1-1-A")

        val response = service.addBarcodedItems(
            "OFR-1", AddDirectItemsRequest(listOf(pkg("PKG-NEW", item(2, "ITEM-2", sku = 101), item(3, "ITEM-3", sku = 202)))),
            "ops@infinity", authToken
        )

        val saved = saved()
        // SKU 101 was already on the GIN: one line, now two units.
        val sku101 = saved.lineItems.single { it.skuId == 101L }
        assertEquals(2, sku101.quantityOrdered)
        assertEquals(2, sku101.quantityShipped)
        assertEquals(listOf("WH1-A-1-1-A", "WH1-B-2-1-B"), sku101.allocatedItems.map { it.location })
        assertEquals("WH2-A-1-1-A", saved.lineItems.single { it.skuId == 202L }.allocatedItems.single().location)

        assertEquals(listOf("PKG-OLD", "PKG-NEW"), saved.packages.map { it.packageBarcode })
        assertEquals("TRUCK-7", saved.packages.last().truckNumber)
        assertTrue(saved.statusHistory.last().notes!!.contains("Added 2 item(s)"))
        assertEquals(1, saved.statusHistory.count { it.currentStatus })
        assertEquals(2, response.addedItems)
        assertEquals(3, response.totalItems)
        assertEquals("GIN-2026-00042", response.ginNumber)
    }

    @Test
    fun `an item Inventory will not ship is left off the GIN`() {
        stubOrder(order())
        ships("ITEM-2", 2L, from = "WH1-B-2-1-B")
        `when`(inventoryClient.changeStorageItemLocation(eq("ITEM-9"), any(ChangeLocationRequest::class.java), anyString()))
            .thenThrow(RuntimeException("Item already shipped"))

        val response = service.addBarcodedItems(
            "OFR-1", AddDirectItemsRequest(listOf(pkg("PKG-NEW", item(2, "ITEM-2", sku = 101), item(9, "ITEM-9", sku = 101)))),
            "ops@infinity", authToken
        )

        assertEquals(listOf("ITEM-9"), response.skippedBarcodes)
        assertEquals(1, response.addedItems)
        assertEquals(listOf(2L), saved().packages.last().assignedItems.map { it.storageItemId })
    }

    @Test
    fun `nothing can be added once the GIN is sent`() {
        stubOrder(order().copy(ginNotification = GinNotification(sentToCustomer = true)))

        val error = assertThrows<IllegalStateException> {
            service.addBarcodedItems("OFR-1", AddDirectItemsRequest(listOf(pkg("PKG-NEW", item(2, "ITEM-2", sku = 101)))), "ops", authToken)
        }
        assertTrue(error.message!!.contains("already been sent"))
        verify(inventoryClient, never()).changeStorageItemLocation(anyString(), any(ChangeLocationRequest::class.java), anyString())
    }

    @Test
    fun `nothing can be added once the order is billed`() {
        val invoiced = order().copy(billingInvoiceId = "INV-1")
        val claimed = order().copy(billingClaim = claim(BillingSourceClaimState.COMMITTED))
        val released = order().copy(billingClaim = claim(BillingSourceClaimState.RELEASED))

        assertTrue(OfrAmendmentService.refusalReason(invoiced)!!.contains("billed"))
        assertTrue(OfrAmendmentService.refusalReason(claimed)!!.contains("billed"))
        assertNull(OfrAmendmentService.refusalReason(released), "a released claim no longer bills the order")
        assertNull(OfrAmendmentService.refusalReason(order()))
    }

    @Test
    fun `an item already on the GIN is refused`() {
        stubOrder(order())

        assertThrows<InvalidOrderRequestException> {
            service.addBarcodedItems("OFR-1", AddDirectItemsRequest(listOf(pkg("PKG-NEW", item(1, "ITEM-1", sku = 101)))), "ops", authToken)
        }
    }

    @Test
    fun `a quantity-based order takes quantity items, not barcodes`() {
        stubOrder(order().copy(fulfillmentType = FulfillmentType.LOCATION_QUANTITY_BASED))

        assertThrows<InvalidOrderRequestException> {
            service.addBarcodedItems("OFR-1", AddDirectItemsRequest(listOf(pkg("PKG-NEW", item(2, "ITEM-2", sku = 101)))), "ops", authToken)
        }
    }

    // Mockito's matchers return null, which Kotlin rejects for non-null parameters.
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(type: Class<T>): T {
        ArgumentMatchers.any(type)
        return null as T
    }

    private fun <T> eq(value: T): T {
        ArgumentMatchers.eq(value)
        return value
    }

    private fun stubOrder(ofr: OrderFulfillmentRequest) {
        `when`(ofrRepository.findByFulfillmentId("OFR-1")).thenReturn(Optional.of(ofr))
    }

    private fun ships(barcode: String, id: Long, from: String) {
        `when`(inventoryClient.changeStorageItemLocation(eq(barcode), any(ChangeLocationRequest::class.java), anyString()))
            .thenReturn(
                ApiResponse.success(
                    ChangeLocationResponse(
                        storageItemId = id, itemCode = barcode, itemType = "SKU_ITEM",
                        previousLocation = LocationChangeInfo(from, "WAREHOUSE"),
                        newLocation = LocationChangeInfo("IN_TRANSIT", "IN_TRANSIT"),
                        locationUpdatedAt = "2026-10-08T10:00:00",
                        transactionCreated = TransactionInfo("TXN-1", "LOCATION_CHANGE", "ops", "OFR-1"),
                        message = "moved"
                    )
                )
            )
    }

    private fun saved(): OrderFulfillmentRequest {
        val captor = ArgumentCaptor.forClass(OrderFulfillmentRequest::class.java)
        verify(ofrRepository).save(captor.capture())
        return captor.value
    }

    private fun item(id: Long, barcode: String, sku: Long) =
        DirectPackageItemDto(itemType = ItemType.SKU_ITEM, storageItemId = id, itemBarcode = barcode, skuId = sku)

    private fun pkg(barcode: String, vararg items: DirectPackageItemDto) = DirectPackageDto(
        packageBarcode = barcode,
        dimensions = PackageDimensionsDto(1.0, 1.0, 1.0, "m"),
        weight = PackageWeightDto(10.0, "kg"),
        items = items.toList()
    )

    private fun claim(state: BillingSourceClaimState) = BillingSourceClaim(
        generationContractVersion = "v1", billingInvoiceId = "INV-1", claimOwnerKey = "k", sourceLineId = "OFR-1",
        accountingPeriod = "2026-10", state = state, claimVersion = 1, expiresAt = null,
        reservedAt = Instant.now(), updatedAt = Instant.now()
    )

    private fun order() = OrderFulfillmentRequest(
        fulfillmentId = "OFR-1",
        accountId = 6994L,
        fulfillmentSource = FulfillmentSource.MANUAL,
        executionApproach = ExecutionApproach.DIRECT_PROCESSING,
        fulfillmentType = FulfillmentType.STANDARD_TASK_BASED,
        fulfillmentStatus = FulfillmentStatus.SHIPPED,
        customerInfo = CustomerInfo(name = "AL BOOM MARINE", email = "ops@alboom.ae"),
        shippingAddress = ShippingAddress(addressLine1 = "Yard 3", city = "Dubai", country = "AE"),
        shippingDetails = ShippingDetails(),
        lineItems = mutableListOf(
            LineItem(
                lineItemId = "LI-1", itemType = ItemType.SKU_ITEM, skuId = 101, quantityOrdered = 1,
                quantityPicked = 1, quantityShipped = 1,
                allocatedItems = mutableListOf(AllocatedItem(storageItemId = 1, location = "WH1-A-1-1-A", picked = true))
            )
        ),
        packages = mutableListOf(
            Package(
                packageId = "PKG-1", packageBarcode = "PKG-OLD", truckNumber = "TRUCK-7",
                assignedItems = mutableListOf(AssignedItem(storageItemId = 1, skuId = 101, itemType = ItemType.SKU_ITEM, itemBarcode = "ITEM-1"))
            )
        ),
        statusHistory = mutableListOf(StatusHistory(status = FulfillmentStatus.SHIPPED, currentStatus = true)),
        ginNumber = "GIN-2026-00042",
        ginNotification = GinNotification(sentToCustomer = false)
    )
}
