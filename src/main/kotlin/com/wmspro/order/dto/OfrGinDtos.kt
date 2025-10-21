package com.wmspro.order.dto

import java.time.LocalDateTime

/**
 * DTOs for OFR GIN Discovery APIs (API 174-176)
 * Phase 8.1: GIN operations for Order Fulfillment Requests
 */

// ========== API 174: Get Companies with Ready GINs ==========

/**
 * API 174 Response: List of companies with ready GINs
 */
data class CompaniesWithReadyGinsResponse(
    val companies: List<CompanyWithReadyGins>,
    val totalCompanies: Int,
    val totalGins: Int
)

/**
 * Company with Ready GINs Details
 */
data class CompanyWithReadyGins(
    val accountId: Long,
    val accountName: String,
    val totalGinsReady: Int
)

// ========== API 175: Get Available GINs for Account ==========

/**
 * API 175 Response: Available GINs for selected account
 */
data class AvailableGinsResponse(
    val accountId: Long,
    val accountName: String,
    val availableGins: List<AvailableGinDetails>,
    val totalGins: Int
)

/**
 * Available GIN Details
 */
data class AvailableGinDetails(
    val ginNumber: String,
    val fulfillmentRequestId: String,
    val fulfillmentStatus: String,
    val packagesCount: Int,
    val packages: List<GinPackageSummary>,
    val createdAt: LocalDateTime
)

/**
 * GIN Package Summary (limited fields for API 175)
 */
data class GinPackageSummary(
    val packageId: String,
    val packageBarcode: String?,
    val dimensions: PackageDimensionsDto?,
    val weight: PackageWeightDto?,
    val dispatchArea: String?,
    val dispatchAreaBarcode: String?,
    val createdAt: LocalDateTime
)

// ========== API 176: Get Packages for GIN ==========

/**
 * API 176 Response: Packages for specific GIN with metadata
 */
data class GinPackagesResponse(
    val ginNumber: String,
    val fulfillmentRequestId: String,
    val accountId: Long,
    val fulfillmentStatus: String,
    val packages: List<GinPackageDetails>,
    val metadata: GinPackagesMetadata
)

/**
 * GIN Package Details (full package info for loading)
 */
data class GinPackageDetails(
    val packageId: String,
    val packageBarcode: String?,
    val dimensions: PackageDimensionsDto?,
    val weight: PackageWeightDto?,
    val dispatchArea: String?,
    val dispatchAreaBarcode: String?,
    val createdAt: LocalDateTime
)

/**
 * GIN Packages Metadata
 */
data class GinPackagesMetadata(
    val totalPackages: Int,
    val totalWeightKg: Double,
    val uniqueDispatchAreas: List<String>,
    val uniqueDispatchAreaCount: Int
)

// ========== Shared DTOs ==========

/**
 * Package Dimensions DTO
 */
data class PackageDimensionsDto(
    val length: Double,
    val width: Double,
    val height: Double,
    val unit: String
)

/**
 * Package Weight DTO
 */
data class PackageWeightDto(
    val value: Double,
    val unit: String
)
