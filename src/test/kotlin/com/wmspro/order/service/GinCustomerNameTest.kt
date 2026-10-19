package com.wmspro.order.service

import com.wmspro.common.service.AccountService
import com.wmspro.common.tenant.TenantContext
import com.wmspro.order.client.InventoryServiceClient
import com.wmspro.order.client.ProductServiceClient
import com.wmspro.order.client.TenantServiceClient
import com.wmspro.order.enums.ExecutionApproach
import com.wmspro.order.enums.FulfillmentSource
import com.wmspro.order.model.CustomerInfo
import com.wmspro.order.model.OrderFulfillmentRequest
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

/**
 * ALMA's old GINs read "Customer 6606": the account's FreighAI customer had been merged away, so the
 * name lookup came back empty. The name saved on the order is used before the bare account number.
 */
class GinCustomerNameTest {
    private val orders = Mockito.mock(OrderFulfillmentRequestRepository::class.java)
    private val accounts = Mockito.mock(AccountService::class.java)
    private val service = GinDataAggregationService(
        orders, Mockito.mock(TenantServiceClient::class.java), accounts,
        Mockito.mock(InventoryServiceClient::class.java), Mockito.mock(ProductServiceClient::class.java)
    )

    @BeforeEach
    fun setUp() = TenantContext.setCurrentTenant("199")

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Test
    fun `an account that no longer resolves shows the customer name saved on the order`() {
        Mockito.`when`(orders.findById("OFR-00205")).thenReturn(Optional.of(order()))
        Mockito.`when`(accounts.fetchAccountNames(anyList(), anyString(), anyString())).thenReturn(emptyMap())

        assertEquals("Alma International", service.aggregateGinData("OFR-00205", "jwt").owner)
    }

    @Test
    fun `a resolvable account still shows its current name`() {
        Mockito.`when`(orders.findById("OFR-00205")).thenReturn(Optional.of(order()))
        Mockito.`when`(accounts.fetchAccountNames(anyList(), anyString(), anyString()))
            .thenReturn(mapOf("6606" to "Alma International General Trading LLC"))

        assertEquals("Alma International General Trading LLC", service.aggregateGinData("OFR-00205", "jwt").owner)
    }

    private fun order() = OrderFulfillmentRequest(
        fulfillmentId = "OFR-00205",
        accountId = 6606L,
        fulfillmentSource = FulfillmentSource.MANUAL,
        executionApproach = ExecutionApproach.DIRECT_PROCESSING,
        customerInfo = CustomerInfo(name = "Alma International", email = "alma_uae@hotmail.com"),
        shippingAddress = ShippingAddress(addressLine1 = "Self collection", city = "Dubai", country = "AE"),
        shippingDetails = ShippingDetails(),
        ginNumber = "GIN-2026-000205"
    )
}
