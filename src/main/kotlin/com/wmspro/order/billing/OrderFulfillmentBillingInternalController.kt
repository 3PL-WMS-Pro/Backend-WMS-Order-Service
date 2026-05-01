package com.wmspro.order.billing

import com.wmspro.common.dto.ApiResponse
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * `/api/v1/internal/orders/{id}/billing-lock` — service-to-service writes
 * from the WMS billing engine. Network-isolated (NOT exposed via gateway).
 *
 * Mirror of
 * [com.wmspro.inbound.billing.ReceivingRecordBillingInternalController].
 */
@RestController
@RequestMapping("/api/v1/internal/orders")
class OrderFulfillmentBillingInternalController(
    private val service: OrderFulfillmentBillingService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PutMapping("/{id}/billing-lock")
    fun setBillingLock(
        @PathVariable id: String,
        @RequestBody request: SetBillingLockRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            service.setBillingLock(id, request.billingInvoiceId, request.billingMonth)
            ResponseEntity.ok(ApiResponse.success(Unit, "OFR locked"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("OFR setBillingLock failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    @DeleteMapping("/{id}/billing-lock")
    fun clearBillingLock(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            service.clearBillingLock(id)
            ResponseEntity.ok(ApiResponse.success(Unit, "OFR lock cleared"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: Exception) {
            logger.error("OFR clearBillingLock failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }
}

data class SetBillingLockRequest(
    @field:NotBlank
    val billingInvoiceId: String,
    @field:NotBlank
    val billingMonth: String
)
