package com.wmspro.order.billing

import com.wmspro.common.dto.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * `PUT /api/v1/orders/{id}/project-code` — admin-facing endpoint to set /
 * change / clear the `projectCode` on an OrderFulfillmentRequest (GIN).
 *
 * Mirror of
 * [com.wmspro.inbound.billing.ReceivingRecordBillingController] minus the
 * cascade flow. Outbound has no cascade — see service-class doc comment.
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderFulfillmentBillingController(
    private val service: OrderFulfillmentBillingService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PutMapping("/{id}/project-code")
    fun updateProjectCode(
        @PathVariable id: String,
        @RequestBody request: UpdateProjectCodeRequest
    ): ResponseEntity<ApiResponse<UpdateProjectCodeResponse>> {
        return try {
            val saved = service.updateProjectCode(id, request.projectCode)
            ResponseEntity.ok(
                ApiResponse.success(
                    UpdateProjectCodeResponse(
                        fulfillmentId = saved.fulfillmentId,
                        projectCode = saved.projectCode
                    ),
                    "Project code updated"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("OFR project-code update not-found: {}", e.message)
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: IllegalStateException) {
            logger.warn("OFR project-code update locked: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Locked"))
        } catch (e: Exception) {
            logger.error("OFR project-code update failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }
}

data class UpdateProjectCodeRequest(
    val projectCode: String? = null
)

data class UpdateProjectCodeResponse(
    val fulfillmentId: String,
    val projectCode: String?
)
