package com.wmspro.order.billing

import com.wmspro.common.billing.BillingSourceClaim
import com.wmspro.common.billing.BillingSourceClaimState
import com.wmspro.common.billing.ReserveBillingSourceClaimRequest
import com.wmspro.common.billing.TransitionBillingSourceClaimRequest
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.order.client.TenantServiceClient
import com.wmspro.order.model.OrderFulfillmentRequest
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

class OrderBillingClaimConflict(message: String) : IllegalStateException(message)

@Service
class OrderFulfillmentBillingClaimService(
    private val mongoTemplate: MongoTemplate,
    private val tenantServiceClient: TenantServiceClient
) {
    fun reserve(id: String, request: ReserveBillingSourceClaimRequest): OrderFulfillmentRequest {
        authorize(request)
        val now = Instant.now()
        val claim = BillingSourceClaim(
            WarehouseJobGenerationContracts.V1,
            request.billingInvoiceId,
            request.claimOwnerKey,
            request.sourceLineId,
            request.accountingPeriod,
            BillingSourceClaimState.RESERVED,
            request.claimVersion,
            request.expiresAt,
            now,
            now
        )
        val query = Query.query(
            Criteria.where("_id").`is`(id)
                .and("billingInvoiceId").`is`(null)
                .orOperator(
                    Criteria.where("billingClaim").exists(false),
                    Criteria.where("billingClaim").`is`(null),
                    Criteria().andOperator(
                        Criteria.where("billingClaim.generationContractVersion").`is`(WarehouseJobGenerationContracts.V1),
                        Criteria.where("billingClaim.claimOwnerKey").`is`(request.claimOwnerKey),
                        Criteria.where("billingClaim.claimVersion").`is`(request.claimVersion),
                        Criteria.where("billingClaim.state").`is`(BillingSourceClaimState.RESERVED)
                    )
                )
        )
        return mongoTemplate.findAndModify(query, Update().set("billingClaim", claim), options(), OrderFulfillmentRequest::class.java)
            ?: conflict(id)
    }

    fun commit(id: String, request: TransitionBillingSourceClaimRequest) =
        transition(id, request, BillingSourceClaimState.RESERVED, BillingSourceClaimState.COMMITTED)

    fun release(id: String, request: TransitionBillingSourceClaimRequest) =
        transition(id, request, BillingSourceClaimState.RESERVED, BillingSourceClaimState.RELEASED)

    fun quarantine(id: String, request: TransitionBillingSourceClaimRequest, error: String) =
        transition(id, request, BillingSourceClaimState.RESERVED, BillingSourceClaimState.MANUAL_REVIEW, error)

    private fun transition(
        id: String,
        request: TransitionBillingSourceClaimRequest,
        from: BillingSourceClaimState,
        to: BillingSourceClaimState,
        error: String? = null
    ): OrderFulfillmentRequest {
        require(request.generationContractVersion == WarehouseJobGenerationContracts.V1) { "CUTOVER_CONFLICT: V1 marker required" }
        val query = Query.query(
            Criteria.where("_id").`is`(id)
                .and("billingInvoiceId").`is`(null)
                .and("billingClaim.generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)
                .and("billingClaim.claimOwnerKey").`is`(request.claimOwnerKey)
                .and("billingClaim.claimVersion").`is`(request.claimVersion)
                .and("billingClaim.state").`is`(from)
        )
        val update = Update().set("billingClaim.state", to)
            .set("billingClaim.updatedAt", Instant.now())
            .inc("billingClaim.claimVersion", 1)
            .unset("billingClaim.expiresAt")
        error?.let { update.set("billingClaim.lastError", it.take(500)) }
        val changed = mongoTemplate.findAndModify(query, update, options(), OrderFulfillmentRequest::class.java)
        if (changed != null) return changed
        val existing = mongoTemplate.findById(id, OrderFulfillmentRequest::class.java)
        if (existing?.billingInvoiceId == null && existing?.billingClaim?.let {
                it.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
                    it.claimOwnerKey == request.claimOwnerKey && it.claimVersion == request.claimVersion + 1 && it.state == to
            } == true) return existing
        return conflict(id)
    }

    private fun authorize(request: ReserveBillingSourceClaimRequest) {
        require(request.generationContractVersion == WarehouseJobGenerationContracts.V1) { "CUTOVER_CONFLICT: V1 marker required" }
        val response = try {
            tenantServiceClient.authorizeBillingClaim(request.billingInvoiceId, request.payloadVersion)
        } catch (e: Exception) {
            throw OrderBillingClaimConflict("Claim authorization unavailable; failing closed")
        }
        if (!response.success || response.data?.generationContractVersion != WarehouseJobGenerationContracts.V1) {
            throw OrderBillingClaimConflict("CUTOVER_CONFLICT: owning invoice is not an authorized V1 revision")
        }
    }

    private fun conflict(id: String): Nothing {
        val existing = mongoTemplate.findById(id, OrderFulfillmentRequest::class.java)
            ?: throw IllegalArgumentException("OrderFulfillmentRequest '$id' not found")
        val detail = if (existing.billingInvoiceId != null) "legacy lock exists" else "owner/version/state mismatch"
        throw OrderBillingClaimConflict("CUTOVER_CONFLICT: OrderFulfillmentRequest '$id' $detail")
    }

    private fun options() = FindAndModifyOptions.options().returnNew(true)
}
