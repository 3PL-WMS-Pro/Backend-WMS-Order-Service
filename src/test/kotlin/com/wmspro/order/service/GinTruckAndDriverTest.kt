package com.wmspro.order.service

import com.wmspro.common.service.AccountService
import com.wmspro.common.tenant.TenantContext
import com.wmspro.order.client.InventoryServiceClient
import com.wmspro.order.client.ProductServiceClient
import com.wmspro.order.client.TenantServiceClient
import com.wmspro.order.enums.ExecutionApproach
import com.wmspro.order.enums.FulfillmentSource
import com.wmspro.order.model.CustomerInfo
import com.wmspro.order.model.LoadingDocuments
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.model.Package
import com.wmspro.order.model.ShippingAddress
import com.wmspro.order.model.ShippingDetails
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The GIN data carries the truck and the driver who took the goods away. New direct orders keep
 * both on their loading documents; orders saved before that still have the truck on every package,
 * so their GIN shows it too.
 */
class GinTruckAndDriverTest {
    private val orders = Mockito.mock(OrderFulfillmentRequestRepository::class.java)
    private val accounts = Mockito.mock(AccountService::class.java)
    private val service = GinDataAggregationService(
        orders, Mockito.mock(TenantServiceClient::class.java), accounts,
        Mockito.mock(InventoryServiceClient::class.java), Mockito.mock(ProductServiceClient::class.java)
    )

    @BeforeEach
    fun setUp() {
        TenantContext.setCurrentTenant("199")
        Mockito.`when`(accounts.fetchAccountNames(anyList(), anyString(), anyString()))
            .thenReturn(mapOf("6994" to "AL BOOM MARINE"))
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Test
    fun `the truck and driver come from the order's loading documents`() {
        stub(
            order(
                loadingDocuments = LoadingDocuments(
                    truckNumber = "DXB A 12345", driverName = "Rahul Kumar", driverPhone = "+971 501234567"
                ),
                packageTruck = "DXB A 12345"
            )
        )

        val gin = service.aggregateGinData("OFR-00533", "jwt")

        assertEquals("DXB A 12345", gin.truckNumber)
        assertEquals("Rahul Kumar", gin.driverName)
        assertEquals("+971 501234567", gin.driverPhone)
    }

    @Test
    fun `the loading documents' truck wins over the packages'`() {
        stub(order(loadingDocuments = LoadingDocuments(truckNumber = "DXB B 777"), packageTruck = "OLD-TRUCK"))

        assertEquals("DXB B 777", service.aggregateGinData("OFR-00533", "jwt").truckNumber)
    }

    @Test
    fun `an order saved before the change shows the truck from its packages and no driver`() {
        stub(order(loadingDocuments = null, packageTruck = "DXB A 12345"))

        val gin = service.aggregateGinData("OFR-00533", "jwt")

        assertEquals("DXB A 12345", gin.truckNumber)
        assertNull(gin.driverName)
        assertNull(gin.driverPhone)
    }

    @Test
    fun `older loading documents without a truck still fall back to the packages`() {
        stub(
            order(
                loadingDocuments = LoadingDocuments(packagePhotosUrls = listOf("https://files/F-1/download")),
                packageTruck = "DXB A 12345"
            )
        )

        assertEquals("DXB A 12345", service.aggregateGinData("OFR-00533", "jwt").truckNumber)
    }

    @Test
    fun `an order with no truck anywhere has none on its GIN`() {
        stub(order(loadingDocuments = null, packageTruck = null))

        val gin = service.aggregateGinData("OFR-00533", "jwt")

        assertNull(gin.truckNumber)
        assertNull(gin.driverName)
        assertNull(gin.driverPhone)
    }

    private fun stub(ofr: OrderFulfillmentRequest) {
        Mockito.`when`(orders.findById("OFR-00533")).thenReturn(Optional.of(ofr))
    }

    private fun order(loadingDocuments: LoadingDocuments?, packageTruck: String?) = OrderFulfillmentRequest(
        fulfillmentId = "OFR-00533",
        accountId = 6994L,
        fulfillmentSource = FulfillmentSource.WEB_PORTAL,
        executionApproach = ExecutionApproach.DIRECT_PROCESSING,
        customerInfo = CustomerInfo(name = "AL BOOM MARINE", email = "ops@alboom.ae"),
        shippingAddress = ShippingAddress(addressLine1 = "Yard 3", city = "Dubai", country = "AE"),
        shippingDetails = ShippingDetails(),
        packages = mutableListOf(
            Package(packageId = "PKG-1", packageBarcode = "PKG-00101", loadedOnTruck = true, truckNumber = packageTruck)
        ),
        ginNumber = "GIN-2026-000532",
        loadingDocuments = loadingDocuments
    )
}
