package com.wmspro.order.service

import com.wmspro.common.jwt.JwtTokenExtractor
import com.wmspro.common.service.AccountService
import com.wmspro.order.dto.*
import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.exception.OrderFulfillmentRequestNotFoundException
import com.wmspro.order.model.GinNotification
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for OFR GIN Operations (APIs 174-176)
 * Phase 8.1: GIN discovery and package retrieval for Order Fulfillment Requests
 * Phase 8.2: GIN PDF generation and email sending
 */
@Service
@Transactional(readOnly = true)
class OfrGinService(
    private val ofrRepository: OrderFulfillmentRequestRepository,
    private val accountService: AccountService,
    private val ginDataAggregationService: GinDataAggregationService,
    private val ginPdfGenerationService: GinPdfGenerationService,
    private val ginEmailService: GinEmailService,
    private val jwtTokenExtractor: JwtTokenExtractor
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * API 174: Get Companies with Ready GINs
     *
     * Returns list of companies/accounts that have GINs ready for loading.
     * Filters OFRs with PACKED/READY_TO_SHIP status, gin_number exists,
     * and all packages dropped at dispatch.
     */
    fun getCompaniesWithReadyGins(authToken: String): CompaniesWithReadyGinsResponse {
        logger.info("API 174: Fetching companies with ready GINs")

        // Query OFRs with ready status
        val readyOFRs = ofrRepository.findAll()
            .filter { ofr ->
                // Filter: fulfillmentStatus in [PACKED, READY_TO_SHIP]
                (ofr.fulfillmentStatus == FulfillmentStatus.PACKED ||
                 ofr.fulfillmentStatus == FulfillmentStatus.READY_TO_SHIP) &&
                // Filter: ginNumber exists (not null)
                ofr.ginNumber != null &&
                // Filter: ALL packages have droppedAtDispatch = true
                ofr.packages.isNotEmpty() &&
                ofr.packages.all { it.droppedAtDispatch }
            }

        logger.info("API 174: Found {} OFRs ready for loading", readyOFRs.size)

        if (readyOFRs.isEmpty()) {
            return CompaniesWithReadyGinsResponse(
                companies = emptyList(),
                totalCompanies = 0,
                totalGins = 0
            )
        }

        // Group by accountId and count GINs
        val accountGinCounts = readyOFRs
            .groupBy { it.accountId }
            .mapValues { (_, ofrs) -> ofrs.size }

        logger.info("API 174: Found {} unique accounts with ready GINs", accountGinCounts.size)

        // Fetch account names
        val accountIds = accountGinCounts.keys.toList()
        val accountNames = try {
            accountService.fetchAccountNames(accountIds, authToken)
        } catch (e: Exception) {
            logger.warn("API 174: Failed to fetch account names: ${e.message}", e)
            emptyMap()
        }

        // Build company list
        val companies = accountGinCounts.map { (accountId, ginCount) ->
            val accountName = accountNames[accountId.toString()] ?: accountId.toString()
            CompanyWithReadyGins(
                accountId = accountId,
                accountName = accountName,
                totalGinsReady = ginCount
            )
        }.sortedByDescending { it.totalGinsReady }

        return CompaniesWithReadyGinsResponse(
            companies = companies,
            totalCompanies = companies.size,
            totalGins = readyOFRs.size
        )
    }

    /**
     * API 175: Get Available GINs for an Account
     *
     * Retrieves list of GINs with PACKED or READY_TO_SHIP status for selected account.
     * Includes package summaries with limited fields.
     */
    fun getAvailableGinsForAccount(accountId: Long, authToken: String): AvailableGinsResponse {
        logger.info("API 175: Fetching available GINs for account: {}", accountId)

        // Validate account exists (fetch name first)
        val accountNames = try {
            accountService.fetchAccountNames(listOf(accountId), authToken)
        } catch (e: Exception) {
            logger.error("API 175: Failed to fetch account details for accountId: {}", accountId, e)
            throw OrderFulfillmentRequestNotFoundException("Account not found: $accountId")
        }

        val accountName = accountNames[accountId.toString()]
        if (accountName == null) {
            logger.error("API 175: Account not found: {}", accountId)
            throw OrderFulfillmentRequestNotFoundException("Account not found: $accountId")
        }

        // Query OFRs for this account with same filtering as API 174
        val accountOFRs = ofrRepository.findAll()
            .filter { ofr ->
                ofr.accountId == accountId &&
                (ofr.fulfillmentStatus == FulfillmentStatus.PACKED ||
                 ofr.fulfillmentStatus == FulfillmentStatus.READY_TO_SHIP) &&
                ofr.ginNumber != null &&
                ofr.packages.isNotEmpty() &&
                ofr.packages.all { it.droppedAtDispatch }
            }
            .sortedByDescending { it.createdAt }

        logger.info("API 175: Found {} GINs for account: {}", accountOFRs.size, accountId)

        // Build available GINs list
        val availableGins = accountOFRs.map { ofr ->
            AvailableGinDetails(
                ginNumber = ofr.ginNumber!!,
                fulfillmentRequestId = ofr.fulfillmentId,
                fulfillmentStatus = ofr.fulfillmentStatus.name,
                packagesCount = ofr.packages.size,
                packages = ofr.packages.map { pkg ->
                    GinPackageSummary(
                        packageId = pkg.packageId,
                        packageBarcode = pkg.packageBarcode,
                        dimensions = pkg.dimensions?.let {
                            PackageDimensionsDto(
                                length = it.length,
                                width = it.width,
                                height = it.height,
                                unit = it.unit
                            )
                        },
                        weight = pkg.weight?.let {
                            PackageWeightDto(
                                value = it.value,
                                unit = it.unit
                            )
                        },
                        dispatchArea = pkg.dispatchArea,
                        dispatchAreaBarcode = pkg.dispatchAreaBarcode,
                        createdAt = pkg.createdAt
                    )
                },
                createdAt = ofr.createdAt
            )
        }

        return AvailableGinsResponse(
            accountId = accountId,
            accountName = accountName,
            availableGins = availableGins,
            totalGins = availableGins.size
        )
    }

    /**
     * API 176: Get Packages for GIN
     *
     * Returns all packages for specific GIN number with dispatch location and details.
     * Validates GIN status and packages are ready for loading.
     */
    fun getPackagesForGin(ginNumber: String): GinPackagesResponse {
        logger.info("API 176: Fetching packages for GIN: {}", ginNumber)

        // Find OFR by ginNumber
        val ofr = ofrRepository.findAll()
            .firstOrNull { it.ginNumber == ginNumber }
            ?: run {
                logger.error("API 176: GIN number not found: {}", ginNumber)
                throw OrderFulfillmentRequestNotFoundException("GIN number not found: $ginNumber")
            }

        // Validate fulfillmentStatus is PACKED or READY_TO_SHIP
        if (ofr.fulfillmentStatus != FulfillmentStatus.PACKED &&
            ofr.fulfillmentStatus != FulfillmentStatus.READY_TO_SHIP) {
            logger.error("API 176: GIN not ready for loading - current status: {}", ofr.fulfillmentStatus)
            throw ValidationException("GIN not ready for loading - current status: ${ofr.fulfillmentStatus}")
        }

        // Validate ALL packages are at dispatch
        if (ofr.packages.isEmpty()) {
            logger.warn("API 176: No packages found for GIN: {}", ginNumber)
        } else if (!ofr.packages.all { it.droppedAtDispatch }) {
            logger.error("API 176: Not all packages are at dispatch area for GIN: {}", ginNumber)
            throw ValidationException("Not all packages are at dispatch area - cannot proceed")
        }

        // Build packages list
        val packages = ofr.packages.map { pkg ->
            GinPackageDetails(
                packageId = pkg.packageId,
                packageBarcode = pkg.packageBarcode,
                dimensions = pkg.dimensions?.let {
                    PackageDimensionsDto(
                        length = it.length,
                        width = it.width,
                        height = it.height,
                        unit = it.unit
                    )
                },
                weight = pkg.weight?.let {
                    PackageWeightDto(
                        value = it.value,
                        unit = it.unit
                    )
                },
                dispatchArea = pkg.dispatchArea,
                dispatchAreaBarcode = pkg.dispatchAreaBarcode,
                createdAt = pkg.createdAt
            )
        }

        // Calculate metadata
        val totalWeightKg = calculateTotalWeightInKg(ofr.packages)
        val uniqueDispatchAreas = ofr.packages
            .mapNotNull { it.dispatchArea }
            .distinct()

        val metadata = GinPackagesMetadata(
            totalPackages = packages.size,
            totalWeightKg = totalWeightKg,
            uniqueDispatchAreas = uniqueDispatchAreas,
            uniqueDispatchAreaCount = uniqueDispatchAreas.size
        )

        logger.info("API 176: Returning {} packages for GIN: {}, total weight: {} kg",
            packages.size, ginNumber, totalWeightKg)

        return GinPackagesResponse(
            ginNumber = ginNumber,
            fulfillmentRequestId = ofr.fulfillmentId,
            accountId = ofr.accountId,
            fulfillmentStatus = ofr.fulfillmentStatus.name,
            packages = packages,
            metadata = metadata
        )
    }

    /**
     * Helper: Get GIN PDF bytes - uses signed copy if available, otherwise generates PDF
     *
     * @param ofr The order fulfillment request
     * @param authToken Authentication token for PDF generation
     * @return PDF bytes (either from signed copy URL or freshly generated)
     */
    private fun getGinPdfBytes(ofr: OrderFulfillmentRequest, authToken: String): ByteArray {
        val signedGinCopyUrl = ofr.ginNotification?.signedGINCopy

        return if (!signedGinCopyUrl.isNullOrBlank()) {
            logger.info("Using signed GIN copy from URL: {} for fulfillment: {}", signedGinCopyUrl, ofr.fulfillmentId)
            try {
                downloadFileFromUrl(signedGinCopyUrl)
            } catch (e: Exception) {
                logger.warn("Failed to download signed GIN copy from URL: {}, falling back to generated PDF. Error: {}",
                    signedGinCopyUrl, e.message)
                generateGinPdf(ofr.fulfillmentId, authToken)
            }
        } else {
            logger.info("No signed GIN copy available, generating PDF for fulfillment: {}", ofr.fulfillmentId)
            generateGinPdf(ofr.fulfillmentId, authToken)
        }
    }

    /**
     * Helper: Download file from URL
     *
     * @param url The URL to download from
     * @return File contents as byte array
     */
    private fun downloadFileFromUrl(url: String): ByteArray {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30000
        connection.readTimeout = 30000

        try {
            if (connection.responseCode != 200) {
                throw RuntimeException("Failed to download file from URL: $url, response code: ${connection.responseCode}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Helper: Calculate total weight in kg from packages
     * Handles weight unit conversion (kg, g, lb)
     */
    private fun calculateTotalWeightInKg(packages: List<com.wmspro.order.model.Package>): Double {
        return packages.mapNotNull { pkg ->
            pkg.weight?.let { weight ->
                when (weight.unit.lowercase()) {
                    "kg" -> weight.value
                    "g", "grams" -> weight.value / 1000.0
                    "lb", "lbs", "pound", "pounds" -> weight.value * 0.453592
                    else -> {
                        logger.warn("Unknown weight unit: ${weight.unit}, defaulting to kg")
                        weight.value
                    }
                }
            }
        }.sum().let {
            // Round to 2 decimal places
            (it * 100).toInt() / 100.0
        }
    }

    // ========== GIN PDF AND EMAIL OPERATIONS ==========

    /**
     * Generate GIN PDF for preview/download
     */
    fun generateGinPdf(fulfillmentId: String, authToken: String): ByteArray {
        logger.info("Generating GIN PDF for fulfillment request: {}", fulfillmentId)

        // Aggregate GIN data
        val ginData = ginDataAggregationService.aggregateGinData(fulfillmentId, authToken)

        // Generate PDF
        return ginPdfGenerationService.generateGinPdf(ginData)
    }

    /**
     * Get GIN PDF for preview - uses signed copy if available, otherwise generates PDF
     * This is used by the preview API to show the appropriate PDF
     *
     * @return Pair of (pdfBytes, filename)
     */
    fun previewGinPdf(fulfillmentId: String, authToken: String): Pair<ByteArray, String> {
        logger.info("Getting GIN PDF for preview - fulfillment request: {}", fulfillmentId)

        // Fetch OFR to check for signed GIN copy
        val ofr = ofrRepository.findById(fulfillmentId).orElse(null)
            ?: throw IllegalArgumentException("Order Fulfillment Request not found: $fulfillmentId")

        val ginNumber = ofr.ginNumber ?: fulfillmentId
        val signedGinCopyUrl = ofr.ginNotification?.signedGINCopy

        val pdfBytes = getGinPdfBytes(ofr, authToken)

        // Determine filename - use GIN number with SIGNED suffix if signed copy, otherwise just GIN number
        val filename = if (!signedGinCopyUrl.isNullOrBlank()) {
            "${ginNumber}-SIGNED.pdf"
        } else {
            "${ginNumber}.pdf"
        }

        return Pair(pdfBytes, filename)
    }

    /**
     * Get default GIN email template content
     */
    fun getDefaultGinEmailTemplate(fulfillmentId: String, authToken: String): GinEmailTemplateResponse {
        logger.info("Getting default GIN email template for fulfillment request: {}", fulfillmentId)

        // Fetch OFR to get customer info
        val ofr = ofrRepository.findById(fulfillmentId).orElse(null)
            ?: throw IllegalArgumentException("Order Fulfillment Request not found: $fulfillmentId")

        val ginNumber = ofr.ginNumber ?: "N/A"
        val customerName = ofr.customerInfo.name

        return ginEmailService.getDefaultGinEmailTemplate(ginNumber, customerName)
    }

    /**
     * Send GIN email with PDF attachment
     * Uses signed GIN copy if available, otherwise generates PDF
     */
    @Transactional
    fun sendGinEmail(fulfillmentId: String, request: SendGinRequest, authToken: String) {
        logger.info("Sending GIN email for fulfillment request: {}", fulfillmentId)

        // 1. Fetch OFR first to check for signed GIN copy
        val ofr = ofrRepository.findById(fulfillmentId).orElse(null)
            ?: throw IllegalArgumentException("Order Fulfillment Request not found: $fulfillmentId")

        val ginNumber = ofr.ginNumber ?: fulfillmentId

        // 2. Get PDF bytes - use signed copy if available, otherwise generate
        val pdfBytes = getGinPdfBytes(ofr, authToken)

        // 3. Send email
        ginEmailService.sendGinEmail(request, pdfBytes, ginNumber)

        // 4. Update fulfillment request with GIN notification details
        val username = jwtTokenExtractor.extractUsername(authToken)

        val updatedGinNotification = GinNotification(
            sentToCustomer = true,
            sentAt = LocalDateTime.now(),
            ginDate = LocalDateTime.now(),
            toEmail = request.toEmail,
            ccEmails = request.ccEmails,
            subject = request.subject,
            emailContent = request.emailContent
        )

        val updatedOFR = ofr.copy(
            ginNotification = updatedGinNotification,
            updatedBy = username,
            updatedAt = LocalDateTime.now()
        )

        ofrRepository.save(updatedOFR)
        logger.info("Order Fulfillment Request updated with GIN sent status")
    }

    /**
     * Add or update GIN attachment
     * If an attachment with the same fileName exists, it will be replaced
     * Otherwise, a new attachment will be added
     */
    @Transactional
    fun addOrUpdateGinAttachment(fulfillmentId: String, request: AddGinAttachmentRequest, authToken: String) {
        logger.info("Adding/updating GIN attachment for fulfillment request: {}", fulfillmentId)

        // Fetch OFR
        val ofr = ofrRepository.findById(fulfillmentId).orElse(null)
            ?: throw IllegalArgumentException("Order Fulfillment Request not found: $fulfillmentId")

        // Get or create GinNotification
        val ginNotification = ofr.ginNotification ?: GinNotification()

        // Add or update attachment
        ginNotification.addOrUpdateAttachment(request.fileName, request.fileUrl)

        // Extract username for audit
        val username = jwtTokenExtractor.extractUsername(authToken)

        // Update OFR
        val updatedOFR = ofr.copy(
            ginNotification = ginNotification,
            updatedBy = username,
            updatedAt = LocalDateTime.now()
        )

        ofrRepository.save(updatedOFR)
        logger.info("GIN attachment added/updated successfully for fulfillment request: {}", fulfillmentId)
    }

    /**
     * Update GIN date and signed GIN copy
     * Updates the ginDate and signedGinCopy fields in the GinNotification
     * If either field is null in the request, the existing value will be preserved
     */
    @Transactional
    fun updateGinDetails(fulfillmentId: String, request: UpdateGinDetailsRequest, authToken: String) {
        logger.info("Updating GIN details for fulfillment request: {} - ginDate: {}, signedGinCopy: {}",
            fulfillmentId, request.ginDate, request.signedGinCopy)

        // Fetch OFR
        val ofr = ofrRepository.findById(fulfillmentId).orElse(null)
            ?: throw IllegalArgumentException("Order Fulfillment Request not found: $fulfillmentId")

        // Get or create GinNotification
        val currentGinNotification = ofr.ginNotification ?: GinNotification()

        // Update GinNotification with new values, preserving existing values if request fields are null
        val updatedGinNotification = currentGinNotification.copy(
            ginDate = request.ginDate ?: currentGinNotification.ginDate,
            signedGINCopy = request.signedGinCopy ?: currentGinNotification.signedGINCopy
        )

        // Extract username for audit
        val username = jwtTokenExtractor.extractUsername(authToken)

        // Update OFR
        val updatedOFR = ofr.copy(
            ginNotification = updatedGinNotification,
            updatedBy = username,
            updatedAt = LocalDateTime.now()
        )

        ofrRepository.save(updatedOFR)
        logger.info("GIN details updated successfully for fulfillment request: {}", fulfillmentId)
    }
}
