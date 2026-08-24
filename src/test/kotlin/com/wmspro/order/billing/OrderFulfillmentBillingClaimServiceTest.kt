package com.wmspro.order.billing

import com.wmspro.common.billing.BillingSourceClaim
import com.wmspro.common.billing.BillingSourceClaimState
import com.wmspro.common.billing.TransitionBillingSourceClaimRequest
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.order.client.TenantServiceClient
import com.wmspro.order.model.OrderFulfillmentRequest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Instant

class OrderFulfillmentBillingClaimServiceTest {
    @Test
    fun `commit adopts the exact already committed result after an indeterminate response`() {
        val mongo = Mockito.mock(MongoTemplate::class.java)
        val service = OrderFulfillmentBillingClaimService(mongo, Mockito.mock(TenantServiceClient::class.java))
        val request = TransitionBillingSourceClaimRequest("owner-1", 4, WarehouseJobGenerationContracts.V1)
        val existing = Mockito.mock(OrderFulfillmentRequest::class.java)
        Mockito.`when`(existing.billingInvoiceId).thenReturn(null)
        Mockito.`when`(existing.billingClaim).thenReturn(claim(BillingSourceClaimState.COMMITTED, 5))
        Mockito.`when`(
            mongo.findAndModify(anyObj(), anyObj(), anyObj(), eqv(OrderFulfillmentRequest::class.java))
        ).thenReturn(null)
        Mockito.`when`(mongo.findById("gin-1", OrderFulfillmentRequest::class.java)).thenReturn(existing)

        assertSame(existing, service.commit("gin-1", request))
    }

    private fun claim(state: BillingSourceClaimState, version: Long) = BillingSourceClaim(
        generationContractVersion = WarehouseJobGenerationContracts.V1,
        billingInvoiceId = "invoice-1",
        claimOwnerKey = "owner-1",
        sourceLineId = "cost-1",
        accountingPeriod = "2026-08",
        state = state,
        claimVersion = version,
        expiresAt = null,
        reservedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyObj(): T { Mockito.any<T>(); return null as T }
    private fun <T : Any> eqv(value: T): T { Mockito.eq(value); return value }
}
