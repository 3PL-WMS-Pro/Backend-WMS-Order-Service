package com.wmspro.order.billing

import com.wmspro.order.client.TenantServiceClient
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Billing-related operations on OrderFulfillmentRequest. Mirror of
 * [com.wmspro.inbound.billing.ReceivingRecordBillingService] minus the
 * cascade — outbound has no cascade because storage items / QBIs being
 * shipped already carry their own projectCode (inherited from the inbound
 * RR they were received under). The GIN's projectCode tags the *shipment*
 * for outbound-movement billing.
 *
 * Two responsibilities:
 *   1. `updateProjectCode` — admin-driven edit. Refused if the GIN is
 *      locked to a billing invoice.
 *   2. `setBillingLock` / `clearBillingLock` — service-to-service writes
 *      from the billing engine.
 */
@Service
class OrderFulfillmentBillingService(
    private val repository: OrderFulfillmentRequestRepository,
    private val tenantServiceClient: TenantServiceClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun updateProjectCode(fulfillmentId: String, newProjectCode: String?): OrderFulfillmentRequest {
        val existing = repository.findById(fulfillmentId).orElseThrow {
            IllegalArgumentException("OrderFulfillmentRequest '$fulfillmentId' not found")
        }
        if (existing.billingInvoiceId != null) {
            throw IllegalStateException(
                "OrderFulfillmentRequest '$fulfillmentId' is locked to billing invoice '${existing.billingInvoiceId}'. " +
                "Cancel the billing run first to change its project code."
            )
        }

        // Audit fix (Finding 9): verify the projectCode exists on the OFR's
        // OWN customer's billing profile, not whatever customer the caller
        // assumed. Defends against bulk-tagging tools that mix customer IDs.
        if (newProjectCode != null) {
            validateProjectCodeBelongsToCustomer(fulfillmentId, existing.accountId, newProjectCode)
        }

        val updated = existing.copy(
            projectCode = newProjectCode,
            updatedAt = LocalDateTime.now()
        )
        val saved = repository.save(updated)
        logger.info("OFR {} projectCode set to '{}'", fulfillmentId, newProjectCode)
        return saved
    }

    private fun validateProjectCodeBelongsToCustomer(
        fulfillmentId: String,
        customerId: Long,
        newProjectCode: String
    ) {
        val response = try {
            tenantServiceClient.getBillingProfile(customerId)
        } catch (e: Exception) {
            logger.warn(
                "validateProjectCode: tenant-service unreachable for customerId={} (OFR={})",
                customerId, fulfillmentId, e
            )
            throw IllegalStateException(
                "Could not validate projectCode '$newProjectCode' against customer $customerId's billing profile (tenant-service unreachable). Try again."
            )
        }
        if (response?.success != true || response.data == null) {
            throw IllegalArgumentException(
                "Customer $customerId has no billing profile yet. Configure rates and projects first " +
                "(Customer Detail → Billing tab) before tagging records."
            )
        }
        val profile = response.data!!
        val match = profile.projects.firstOrNull { it.projectCode == newProjectCode }
        if (match == null) {
            throw IllegalArgumentException(
                "Project '$newProjectCode' is not configured on customer $customerId's billing profile. " +
                "Either pick an existing project from the dropdown or add it on the Billing tab. " +
                "(If you intended a different customer, double-check the record IDs you're tagging.)"
            )
        }
    }

    @Transactional
    fun setBillingLock(fulfillmentId: String, billingInvoiceId: String, billingMonth: String): OrderFulfillmentRequest {
        val existing = repository.findById(fulfillmentId).orElseThrow {
            IllegalArgumentException("OrderFulfillmentRequest '$fulfillmentId' not found")
        }
        if (existing.billingInvoiceId != null && existing.billingInvoiceId != billingInvoiceId) {
            throw IllegalStateException(
                "OrderFulfillmentRequest '$fulfillmentId' already locked to billing invoice '${existing.billingInvoiceId}'"
            )
        }
        return repository.save(
            existing.copy(
                billingInvoiceId = billingInvoiceId,
                billingMonth = billingMonth,
                updatedAt = LocalDateTime.now()
            )
        )
    }

    @Transactional
    fun clearBillingLock(fulfillmentId: String): OrderFulfillmentRequest {
        val existing = repository.findById(fulfillmentId).orElseThrow {
            IllegalArgumentException("OrderFulfillmentRequest '$fulfillmentId' not found")
        }
        if (existing.billingInvoiceId == null) return existing
        return repository.save(
            existing.copy(
                billingInvoiceId = null,
                billingMonth = null,
                updatedAt = LocalDateTime.now()
            )
        )
    }
}
