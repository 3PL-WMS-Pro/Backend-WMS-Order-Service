package com.wmspro.order.dto

import com.wmspro.order.enums.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/**
 * Direct OFR Processing DTOs
 * For web-based express fulfillment without task creation
 */

// Main Request DTO
data class CreateDirectOfrRequest(
    @field:NotNull(message = "Account ID is required")
    val accountId: Long,

    // Step 1: Basic Details
    @field:NotNull(message = "Customer info is required")
    val customerInfo: CustomerInfoDto,

    @field:NotNull(message = "Shipping address is required")
    val shippingAddress: ShippingAddressDto,

    @field:NotNull(message = "GIN date is required")
    val ginDate: LocalDateTime,

    val orderValue: OrderValueDto? = null,

    val shippingDetails: DirectShippingDetailsDto? = null,

    // Step 2: Packages & Items
    @field:NotEmpty(message = "Packages cannot be empty")
    val packages: List<DirectPackageDto>,

    @field:NotNull(message = "AWB condition is required")
    val awbCondition: AwbCondition,

    // Step 3: Loading Details
    @field:NotBlank(message = "Truck number is required")
    val truckNumber: String,

    val loadingDocuments: LoadingDocumentsDto? = null,

    // Optional Fields
    val fulfillmentSource: FulfillmentSource = FulfillmentSource.WEB_PORTAL,
    val priority: Priority = Priority.STANDARD,
    val notes: String? = null,
    val externalOrderId: String? = null,
    val externalOrderNumber: String? = null
)

data class DirectShippingDetailsDto(
    val carrier: String? = null,
    val requestedServiceType: ServiceType? = null
)

data class DirectPackageDto(
    val packageBarcode: String? = null,

    @field:NotNull(message = "Package dimensions are required")
    val dimensions: PackageDimensionsDto,

    @field:NotNull(message = "Package weight is required")
    val weight: PackageWeightDto,

    @field:NotEmpty(message = "Package items cannot be empty")
    val items: List<DirectPackageItemDto>
)

data class DirectPackageItemDto(
    @field:NotNull(message = "Item type is required")
    val itemType: ItemType,

    @field:NotNull(message = "Storage item ID is required")
    val storageItemId: Long,

    @field:NotBlank(message = "Item barcode is required")
    val itemBarcode: String,

    val skuId: Long? = null  // Required if itemType is SKU_ITEM
)

data class LoadingDocumentsDto(
    val signedGinUrl: String? = null,
    val packagePhotosUrls: List<String> = listOf(),
    val truckDriverPhotoUrl: String? = null,
    val truckDriverIdProofUrl: String? = null
)

// Response DTO
data class DirectOfrResponse(
    val fulfillmentId: String,
    val ginNumber: String,
    val fulfillmentStatus: String,
    val accountId: Long,
    val totalPackages: Int,
    val totalItems: Int,
    val truckNumber: String,
    val awbGenerated: Boolean,
    val awbDetails: AwbDetailsResponse? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class AwbDetailsResponse(
    val shipmentId: String,
    val awbNumber: String,
    val carrier: String,
    val trackingUrl: String?,
    val shippingLabelPdf: String?,
    val awbPdf: String?
)
