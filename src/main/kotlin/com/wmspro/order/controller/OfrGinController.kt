package com.wmspro.order.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.*
import com.wmspro.order.service.OfrGinService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for OFR GIN Operations (API 174-176)
 * Phase 8.1: GIN discovery and package retrieval for Order Fulfillment Requests
 * Phase 8.2: GIN PDF generation and email sending
 */
@RestController
@RequestMapping("/api/v1/orders/gin")
class OfrGinController(
    private val ofrGinService: OfrGinService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * API 174: Get Companies with Ready GINs
     * GET /api/v1/orders/gin/companies-with-ready-gins
     *
     * Returns list of companies/accounts that have GINs ready for loading
     */
    @GetMapping("/companies-with-ready-gins")
    fun getCompaniesWithReadyGins(
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CompaniesWithReadyGinsResponse>> {
        logger.info("GET /api/v1/orders/gin/companies-with-ready-gins - Get companies with ready GINs")

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            val response = ofrGinService.getCompaniesWithReadyGins(authToken)

            logger.info("Found {} companies with {} total GINs ready for loading",
                response.totalCompanies, response.totalGins)

            ResponseEntity.ok(
                ApiResponse.success(
                    response,
                    "Companies with ready GINs retrieved successfully"
                )
            )

        } catch (e: Exception) {
            logger.error("Error fetching companies with ready GINs: ${e.message}", e)
            ResponseEntity
                .badRequest()
                .body(
                    ApiResponse.error(
                        e.message ?: "Failed to fetch companies with ready GINs"
                    )
                )
        }
    }

    /**
     * API 175: Get Available GINs for an Account
     * GET /api/v1/orders/gin/accounts/{accountId}/available-gins
     *
     * Retrieves list of GINs for selected company/account
     */
    @GetMapping("/accounts/{accountId}/available-gins")
    fun getAvailableGinsForAccount(
        @PathVariable accountId: Long,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<AvailableGinsResponse>> {
        logger.info("GET /api/v1/orders/gin/accounts/{}/available-gins - Get available GINs for account", accountId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            val response = ofrGinService.getAvailableGinsForAccount(accountId, authToken)

            logger.info("Found {} GINs ready for loading for account: {} ({})",
                response.totalGins, accountId, response.accountName)

            ResponseEntity.ok(
                ApiResponse.success(
                    response,
                    "Available GINs retrieved successfully"
                )
            )

        } catch (e: com.wmspro.order.exception.OrderFulfillmentRequestNotFoundException) {
            logger.error("Account not found: {}", accountId)
            ResponseEntity
                .status(404)
                .body(
                    ApiResponse.error<AvailableGinsResponse>(
                        e.message ?: "Account not found"
                    )
                )

        } catch (e: Exception) {
            logger.error("Error fetching available GINs for account {}: ${e.message}", accountId, e)
            ResponseEntity
                .badRequest()
                .body(
                    ApiResponse.error(
                        e.message ?: "Failed to fetch available GINs"
                    )
                )
        }
    }

    /**
     * API 176: Get Packages for GIN
     * GET /api/v1/orders/gin/{ginNumber}/packages
     *
     * Returns all packages for specific GIN number
     */
    @GetMapping("/{ginNumber}/packages")
    fun getPackagesForGin(
        @PathVariable ginNumber: String
    ): ResponseEntity<ApiResponse<GinPackagesResponse>> {
        logger.info("GET /api/v1/orders/gin/{}/packages - Get packages for GIN", ginNumber)

        return try {
            val response = ofrGinService.getPackagesForGin(ginNumber)

            logger.info("Returning {} packages for GIN: {}, total weight: {} kg",
                response.metadata.totalPackages, ginNumber, response.metadata.totalWeightKg)

            ResponseEntity.ok(
                ApiResponse.success(
                    response,
                    "Packages for GIN retrieved successfully"
                )
            )

        } catch (e: com.wmspro.order.exception.OrderFulfillmentRequestNotFoundException) {
            logger.error("GIN not found: {}", ginNumber)
            ResponseEntity
                .status(404)
                .body(
                    ApiResponse.error<GinPackagesResponse>(
                        e.message ?: "GIN not found"
                    )
                )

        } catch (e: jakarta.validation.ValidationException) {
            logger.error("Validation error for GIN {}: ${e.message}", ginNumber)
            ResponseEntity
                .badRequest()
                .body(
                    ApiResponse.error(
                        e.message ?: "GIN not ready for loading"
                    )
                )

        } catch (e: Exception) {
            logger.error("Error fetching packages for GIN {}: ${e.message}", ginNumber, e)
            ResponseEntity
                .badRequest()
                .body(
                    ApiResponse.error(
                        e.message ?: "Failed to fetch packages for GIN"
                    )
                )
        }
    }

    // ========== GIN PDF AND EMAIL OPERATIONS ==========

    /**
     * Preview GIN PDF
     * GET /api/v1/orders/gin/{fulfillmentId}/preview
     *
     * Returns signed GIN copy if available, otherwise generates GIN PDF for preview
     */
    @GetMapping("/{fulfillmentId}/preview")
    fun previewGin(
        @PathVariable fulfillmentId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ByteArray> {
        logger.info("GET /api/v1/orders/gin/{}/preview", fulfillmentId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            val pdfBytes = ofrGinService.previewGinPdf(fulfillmentId, authToken)

            ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=\"GIN_$fulfillmentId.pdf\"")
                .body(pdfBytes)
        } catch (e: IllegalArgumentException) {
            logger.error("Order Fulfillment Request not found: $fulfillmentId", e)
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: Exception) {
            logger.error("Error generating GIN PDF", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * Download GIN PDF
     * GET /api/v1/orders/gin/{fulfillmentId}/download
     *
     * Generates and downloads GIN PDF file to disk without sending email
     */
    @GetMapping("/{fulfillmentId}/download")
    fun downloadGin(
        @PathVariable fulfillmentId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ByteArray> {
        logger.info("GET /api/v1/orders/gin/{}/download", fulfillmentId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            val pdfBytes = ofrGinService.generateGinPdf(fulfillmentId, authToken)

            ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"GIN_$fulfillmentId.pdf\"")
                .body(pdfBytes)
        } catch (e: IllegalArgumentException) {
            logger.error("Order Fulfillment Request not found: $fulfillmentId", e)
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: Exception) {
            logger.error("Error generating GIN PDF", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * Get Default GIN Email Template
     * GET /api/v1/orders/gin/{fulfillmentId}/email-template
     *
     * Returns the default email template content for sending GIN
     */
    @GetMapping("/{fulfillmentId}/email-template")
    fun getGinEmailTemplate(
        @PathVariable fulfillmentId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<GinEmailTemplateResponse>> {
        logger.info("GET /api/v1/orders/gin/{}/email-template", fulfillmentId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            val emailTemplate = ofrGinService.getDefaultGinEmailTemplate(fulfillmentId, authToken)

            ResponseEntity.ok(
                ApiResponse.success(
                    emailTemplate,
                    "Email template retrieved successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Order Fulfillment Request not found: $fulfillmentId", e)
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(
                    e.message ?: "Order Fulfillment Request not found"
                )
            )
        } catch (e: Exception) {
            logger.error("Error getting email template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    "Internal server error: ${e.message}"
                )
            )
        }
    }

    /**
     * Send GIN Email
     * POST /api/v1/orders/gin/{fulfillmentId}/send
     *
     * Generates GIN PDF and sends it via email to the specified recipients
     */
    @PostMapping("/{fulfillmentId}/send")
    fun sendGinEmail(
        @PathVariable fulfillmentId: String,
        @Valid @RequestBody request: SendGinRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<String>> {
        logger.info("POST /api/v1/orders/gin/{}/send", fulfillmentId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            ofrGinService.sendGinEmail(fulfillmentId, request, authToken)

            ResponseEntity.ok(
                ApiResponse.success(
                    "Email sent to ${request.toEmail}",
                    "GIN email sent successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Order Fulfillment Request not found: $fulfillmentId", e)
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(
                    e.message ?: "Order Fulfillment Request not found"
                )
            )
        } catch (e: IllegalStateException) {
            logger.error("Configuration error: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    e.message ?: "Configuration error"
                )
            )
        } catch (e: Exception) {
            logger.error("Error sending GIN email", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    "Internal server error: ${e.message}"
                )
            )
        }
    }

    /**
     * Add or Update GIN Attachment
     * POST /api/v1/orders/gin/{fulfillmentId}/attachments
     *
     * Adds or updates an attachment in the GIN notification.
     * If an attachment with the same fileName exists, it will be replaced.
     */
    @PostMapping("/{fulfillmentId}/attachments")
    fun addOrUpdateGinAttachment(
        @PathVariable fulfillmentId: String,
        @Valid @RequestBody request: AddGinAttachmentRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<String>> {
        logger.info("POST /api/v1/orders/gin/{}/attachments", fulfillmentId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            ofrGinService.addOrUpdateGinAttachment(fulfillmentId, request, authToken)

            ResponseEntity.ok(
                ApiResponse.success(
                    "Attachment added/updated successfully",
                    "GIN attachment processed successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Order Fulfillment Request not found: $fulfillmentId", e)
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(
                    e.message ?: "Order Fulfillment Request not found"
                )
            )
        } catch (e: Exception) {
            logger.error("Error adding/updating GIN attachment", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    "Internal server error: ${e.message}"
                )
            )
        }
    }

    /**
     * Update GIN Date and Signed Copy
     * PUT /api/v1/orders/gin/{fulfillmentId}/gin-details
     *
     * Updates the GIN date and signed GIN copy URL
     */
    @PutMapping("/{fulfillmentId}/gin-details")
    fun updateGinDetails(
        @PathVariable fulfillmentId: String,
        @Valid @RequestBody request: UpdateGinDetailsRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<String>> {
        logger.info("PUT /api/v1/orders/gin/{}/gin-details - Updating GIN date and signed copy", fulfillmentId)

        val authToken = httpRequest.getHeader("Authorization") ?: ""

        return try {
            ofrGinService.updateGinDetails(fulfillmentId, request, authToken)

            ResponseEntity.ok(
                ApiResponse.success(
                    "GIN details updated successfully",
                    "GIN date and signed copy updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Order Fulfillment Request not found: $fulfillmentId", e)
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(
                    e.message ?: "Order Fulfillment Request not found"
                )
            )
        } catch (e: Exception) {
            logger.error("Error updating GIN details: $fulfillmentId", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    "Internal server error: ${e.message}"
                )
            )
        }
    }
}
