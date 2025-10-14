package com.wmspro.order.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.jwt.JwtTokenExtractor
import com.wmspro.order.dto.*
import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.enums.OfrStage
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
    private val orderFulfillmentDetailsService: com.wmspro.order.service.OrderFulfillmentDetailsService,
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

    /**
     * Get OFR Stage Summary
     * GET /api/v1/orders/fulfillment-requests/stage-summary
     *
     * Returns count of OFRs in each stage for web dashboard
     */
    @GetMapping("/stage-summary")
    fun getOfrStageSummary(
        @RequestParam(required = false) accountId: Long?
    ): ResponseEntity<ApiResponse<OfrStageSummaryResponse>> {
        logger.info("GET /api/v1/orders/fulfillment-requests/stage-summary" +
                if (accountId != null) " for account: $accountId" else "")

        return try {
            val summary = orderFulfillmentService.getOfrStageSummary(accountId)
            ResponseEntity.ok(ApiResponse.success(summary, "OFR stage summary retrieved successfully"))
        } catch (e: Exception) {
            logger.error("Error retrieving OFR stage summary", e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve stage summary"))
        }
    }

    /**
     * Get OFRs by Stage for Web List Views
     * GET /api/v1/orders/fulfillment-requests/by-stage
     *
     * Returns paginated list of OFRs filtered by stage with optional search and date filters
     */
    @GetMapping("/by-stage")
    fun getOfrsByStage(
        @RequestParam stage: OfrStage,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) dateFrom: String?,
        @RequestParam(required = false) dateTo: String?,
        @RequestParam(required = false) accountId: Long?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<PageResponse<OfrListItemResponse>>> {
        logger.info("GET /api/v1/orders/fulfillment-requests/by-stage?stage={}&page={}&size={}",
            stage, page, size)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""

            // Parse date parameters if provided
            val dateFromParsed = dateFrom?.let {
                try {
                    java.time.LocalDateTime.parse(it, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
                } catch (e: Exception) {
                    logger.warn("Invalid dateFrom format: $it")
                    null
                }
            }

            val dateToParsed = dateTo?.let {
                try {
                    java.time.LocalDateTime.parse(it, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
                } catch (e: Exception) {
                    logger.warn("Invalid dateTo format: $it")
                    null
                }
            }

            // Convert from 1-based (user-facing) to 0-based (Spring Data) indexing
            val result = orderFulfillmentService.getOfrsByStage(
                stage = stage,
                page = page - 1,  // Convert to 0-based indexing
                size = size,
                searchTerm = search,
                dateFrom = dateFromParsed,
                dateTo = dateToParsed,
                accountId = accountId,
                authToken = authToken
            )

            ResponseEntity.ok(ApiResponse.success(result, "OFRs retrieved successfully"))

        } catch (e: Exception) {
            logger.error("Error retrieving OFRs by stage: ${e.message}", e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve OFRs"))
        }
    }

    // ========== OFR DETAIL PAGE APIS ==========

    /**
     * API 108: Get OFR Header
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{ofrId}/header
     */
    @GetMapping("/{ofrId}/header")
    fun getOfrHeader(
        @PathVariable ofrId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<OfrHeaderResponse>> {
        logger.info("API 108: Get OFR Header - ofrId: {}", ofrId)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""
            val header = orderFulfillmentDetailsService.getOfrHeader(ofrId, authToken)
            ResponseEntity.ok(ApiResponse.success(header, "OFR header retrieved successfully"))

        } catch (e: IllegalArgumentException) {
            logger.error("OFR not found: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.message ?: "OFR not found"))

        } catch (e: Exception) {
            logger.error("Error retrieving OFR header: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve OFR header"))
        }
    }

    /**
     * API 109: Get Picking Details Tab
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{ofrId}/picking-details-tab
     */
    @GetMapping("/{ofrId}/picking-details-tab")
    fun getPickingDetailsTab(
        @PathVariable ofrId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<PickingDetailsTabResponse>> {
        logger.info("API 109: Get Picking Details Tab - ofrId: {}", ofrId)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""
            val pickingDetails = orderFulfillmentDetailsService.getPickingDetailsTab(ofrId, authToken)
            ResponseEntity.ok(ApiResponse.success(pickingDetails, "Picking details retrieved successfully"))

        } catch (e: IllegalArgumentException) {
            logger.error("Error retrieving picking details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve picking details"))

        } catch (e: Exception) {
            logger.error("Error retrieving picking details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve picking details"))
        }
    }

    /**
     * API 110: Get Pack Move Pending Tab
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{ofrId}/pack-move-pending-tab
     */
    @GetMapping("/{ofrId}/pack-move-pending-tab")
    fun getPackMovePendingTab(
        @PathVariable ofrId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<PackMovePendingTabResponse>> {
        logger.info("API 110: Get Pack Move Pending Tab - ofrId: {}", ofrId)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""
            val packMoveDetails = orderFulfillmentDetailsService.getPackMovePendingTab(ofrId, authToken)
            ResponseEntity.ok(ApiResponse.success(packMoveDetails, "Pack move details retrieved successfully"))

        } catch (e: IllegalArgumentException) {
            logger.error("Error retrieving pack move details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve pack move details"))

        } catch (e: Exception) {
            logger.error("Error retrieving pack move details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve pack move details"))
        }
    }

    /**
     * API 111: Get Pick Pack Move Details Tab
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{ofrId}/pick-pack-move-details-tab
     */
    @GetMapping("/{ofrId}/pick-pack-move-details-tab")
    fun getPickPackMoveDetailsTab(
        @PathVariable ofrId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<PickPackMoveDetailsTabResponse>> {
        logger.info("API 111: Get Pick Pack Move Details Tab - ofrId: {}", ofrId)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""
            val pickPackMoveDetails = orderFulfillmentDetailsService.getPickPackMoveDetailsTab(ofrId, authToken)
            ResponseEntity.ok(ApiResponse.success(pickPackMoveDetails, "Pick pack move details retrieved successfully"))

        } catch (e: IllegalArgumentException) {
            logger.error("Error retrieving pick pack move details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve pick pack move details"))

        } catch (e: Exception) {
            logger.error("Error retrieving pick pack move details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve pick pack move details"))
        }
    }

    /**
     * API 112: Get Ready To Dispatch Tab
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{ofrId}/ready-to-dispatch-tab
     */
    @GetMapping("/{ofrId}/ready-to-dispatch-tab")
    fun getReadyToDispatchTab(
        @PathVariable ofrId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<ReadyToDispatchTabResponse>> {
        logger.info("API 112: Get Ready To Dispatch Tab - ofrId: {}", ofrId)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""
            val readyToDispatchDetails = orderFulfillmentDetailsService.getReadyToDispatchTab(ofrId, authToken)
            ResponseEntity.ok(ApiResponse.success(readyToDispatchDetails, "Ready to dispatch details retrieved successfully"))

        } catch (e: IllegalArgumentException) {
            logger.error("Error retrieving ready to dispatch details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve ready to dispatch details"))

        } catch (e: Exception) {
            logger.error("Error retrieving ready to dispatch details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve ready to dispatch details"))
        }
    }

    /**
     * API 113: Get Loading Done & GIN Pending Tab
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{ofrId}/loading-tab
     */
    @GetMapping("/{ofrId}/loading-tab")
    fun getLoadingTab(
        @PathVariable ofrId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<LoadingTabResponse>> {
        logger.info("API 113: Get Loading Tab - ofrId: {}", ofrId)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""
            val loadingDetails = orderFulfillmentDetailsService.getLoadingTab(ofrId, authToken)
            ResponseEntity.ok(ApiResponse.success(loadingDetails, "Loading details retrieved successfully"))

        } catch (e: IllegalArgumentException) {
            logger.error("Error retrieving loading details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve loading details"))

        } catch (e: Exception) {
            logger.error("Error retrieving loading details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve loading details"))
        }
    }

    /**
     * API 114: Get GIN Sent Tab
     * Method: GET
     * Endpoint: /api/v1/orders/fulfillment-requests/{ofrId}/gin-sent-tab
     */
    @GetMapping("/{ofrId}/gin-sent-tab")
    fun getGinSentTab(
        @PathVariable ofrId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<GinSentTabResponse>> {
        logger.info("API 114: Get GIN Sent Tab - ofrId: {}", ofrId)

        return try {
            val authToken = httpRequest.getHeader("Authorization") ?: ""
            val ginSentDetails = orderFulfillmentDetailsService.getGinSentTab(ofrId, authToken)
            ResponseEntity.ok(ApiResponse.success(ginSentDetails, "GIN sent details retrieved successfully"))

        } catch (e: IllegalArgumentException) {
            logger.error("Error retrieving GIN sent details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve GIN sent details"))

        } catch (e: Exception) {
            logger.error("Error retrieving GIN sent details: {}", ofrId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(e.message ?: "Failed to retrieve GIN sent details"))
        }
    }
}
