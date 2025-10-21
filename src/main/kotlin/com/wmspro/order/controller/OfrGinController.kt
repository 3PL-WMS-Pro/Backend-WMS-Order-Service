package com.wmspro.order.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.*
import com.wmspro.order.service.OfrGinService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for OFR GIN Operations (API 174-176)
 * Phase 8.1: GIN discovery and package retrieval for Order Fulfillment Requests
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
}
