package com.wmspro.order.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.jwt.JwtTokenExtractor
import com.wmspro.order.dto.CreateOfrRequest
import com.wmspro.order.dto.OfrResponse
import com.wmspro.order.dto.PageResponse
import com.wmspro.order.dto.ChangeOfrStatusToPickupDoneRequest
import com.wmspro.order.dto.ChangeOfrStatusToPickupDoneResponse
import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.service.OrderFulfillmentService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/orders/fulfillment-requests")
class OrderFulfillmentController(
    private val orderFulfillmentService: OrderFulfillmentService,
    private val jwtTokenExtractor: JwtTokenExtractor
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * API 133: Create Order Fulfillment Request with Task Generation
     * Method: POST
     * Endpoint: /api/v1/orders/fulfillment-requests
     */
    @PostMapping
    fun createOrderFulfillmentRequest(
        @Valid @RequestBody request: CreateOfrRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<OfrResponse>> {
        logger.info("API 133: Create Order Fulfillment Request for account: {}", request.accountId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""
        val username = try {
            jwtTokenExtractor.extractUsername(authToken)
        } catch (e: Exception) {
            null
        }

        return try {
            val ofr = orderFulfillmentService.createOrderFulfillmentRequest(request, username, authToken)
            val response = OfrResponse.from(ofr)

            logger.info("Order Fulfillment Request created successfully: {}", ofr.fulfillmentId)

            ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Order Fulfillment Request created successfully"))

        } catch (e: Exception) {
            logger.error("Error creating Order Fulfillment Request", e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.message ?: "Failed to create Order Fulfillment Request"))
        }
    }

    /**
     * Get All OFRs with optional filtering
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests
     */
    @GetMapping
    fun getAllOFRs(
        @RequestParam(required = false) accountId: Long?,
        @RequestParam(required = false) fulfillmentStatus: FulfillmentStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<ApiResponse<PageResponse<OfrResponse>>> {
        logger.info("Get All OFRs - accountId: {}, status: {}, page: {}, size: {}",
            accountId, fulfillmentStatus, page, size)

        return try {
            val ofrPage = orderFulfillmentService.getAllOFRs(accountId, fulfillmentStatus, page, size)

            val pageResponse = PageResponse(
                data = ofrPage.content.map { OfrResponse.from(it) },
                page = page,
                limit = size,
                totalItems = ofrPage.totalElements,
                totalPages = ofrPage.totalPages,
                hasNext = ofrPage.hasNext(),
                hasPrevious = ofrPage.hasPrevious()
            )

            ResponseEntity.ok(ApiResponse.success(pageResponse, "OFRs retrieved successfully"))

        } catch (e: Exception) {
            logger.error("Error retrieving OFRs", e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve OFRs"))
        }
    }

    /**
     * Get OFR By ID
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{fulfillmentId}
     */
    @GetMapping("/{fulfillmentId}")
    fun getOfrById(
        @PathVariable fulfillmentId: String
    ): ResponseEntity<ApiResponse<OfrResponse>> {
        logger.info("Get OFR By ID: {}", fulfillmentId)

        return try {
            val ofr = orderFulfillmentService.getOfrById(fulfillmentId)
            val response = OfrResponse.from(ofr)

            ResponseEntity.ok(ApiResponse.success(response, "OFR retrieved successfully"))

        } catch (e: Exception) {
            logger.error("Error retrieving OFR: {}", fulfillmentId, e)
            val status = if (e.message?.contains("not found", ignoreCase = true) == true) {
                HttpStatus.NOT_FOUND
            } else {
                HttpStatus.INTERNAL_SERVER_ERROR
            }

            ResponseEntity
                .status(status)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve OFR"))
        }
    }

    /**
     * API 141: Change OFR Status to "PICKUP_DONE"
     * PUT /api/v1/orders/fulfillment-requests/{fulfillmentRequestId}/pickup-done
     *
     * Updates OFR status after picking completion and creates Pack_Move task if needed
     */
    @PutMapping("/{fulfillmentRequestId}/pickup-done")
    fun changeOfrStatusToPickupDone(
        @PathVariable fulfillmentRequestId: String,
        @RequestBody request: ChangeOfrStatusToPickupDoneRequest,
        @RequestHeader("Authorization") authToken: String
    ): ResponseEntity<ApiResponse<ChangeOfrStatusToPickupDoneResponse>> {
        logger.info("PUT /api/v1/orders/fulfillment-requests/$fulfillmentRequestId/pickup-done")

        return try {
            val response = orderFulfillmentService.changeOfrStatusToPickupDone(fulfillmentRequestId, request, authToken)

            ResponseEntity.ok(
                ApiResponse.success(response, "OFR status updated to PICKUP_DONE successfully")
            )
        } catch (e: Exception) {
            logger.error("Error changing OFR status to PICKUP_DONE: ${e.message}", e)
            val status = if (e.message?.contains("not found", ignoreCase = true) == true) {
                HttpStatus.NOT_FOUND
            } else {
                HttpStatus.INTERNAL_SERVER_ERROR
            }

            ResponseEntity
                .status(status)
                .body(ApiResponse.error(e.message ?: "Failed to update OFR status"))
        }
    }
}
