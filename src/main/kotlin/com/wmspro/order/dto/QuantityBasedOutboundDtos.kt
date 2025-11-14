package com.wmspro.order.dto

import com.wmspro.order.enums.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/**
 * DTOs for Quantity-Based Outbound Fulfillment
 * Supports Scenario 2 (Container-Based) and Scenario 3 (Location-Based) outbound processes
 */

// ============================================
// API 1: Container-Based Quantity Picking (Scenario 2)
// ============================================

/**
 * Request DTO for creating OFR with container-based quantity picking
 * POST /api/v1/orders/fulfillment-requests/create-container-quantity-based
 */
data class CreateContainerQuantityBasedRequest(
    @field:NotNull(message = "Account ID is required")
    val accountId: Long,

    @field:NotNull(message = "Customer info is required")
    @field:Valid
    val customerInfo: CustomerInfoDto,

    @field:NotNull(message = "Shipping address is required")
    @field:Valid
    val shippingAddress: ShippingAddressDto,

    @field:NotEmpty(message = "At least one item must be picked")
    @field:Valid
    val itemsPicked: List<ItemPickedContainerBasedDto>,

    @field:NotEmpty(message = "At least one package must be provided")
    @field:Valid
    val packages: List<PackageDto>,

    @field:NotNull(message = "Shipping details are required")
    @field:Valid
    val shippingDetails: ShippingDetailsDto,

    val notes: String? = null,
    val tags: List<String> = listOf(),
    val customFields: Map<String, String> = mapOf()
)

/**
 * Item picked with container source information (Scenario 2)
 */
data class ItemPickedContainerBasedDto(
    @field:NotNull(message = "SKU ID is required")
    val skuId: Long,

    @field:NotNull(message = "Total quantity picked is required")
    @field:Min(value = 1, message = "Total quantity picked must be at least 1")
    val totalQuantityPicked: Int,

    @field:NotEmpty(message = "At least one source container must be provided")
    @field:Valid
    val sourceContainers: List<SourceContainerDto>
)

/**
 * Source container information (Scenario 2)
 */
data class SourceContainerDto(
    @field:NotBlank(message = "Container barcode is required")
    val containerBarcode: String,

    @field:NotBlank(message = "Quantity inventory ID is required")
    val quantityInventoryId: String,

    @field:NotNull(message = "Quantity picked is required")
    @field:Min(value = 1, message = "Quantity picked must be at least 1")
    val quantityPicked: Int,

    @field:NotBlank(message = "Location code is required")
    val locationCode: String
)

/**
 * Response DTO for container-based OFR creation
 */
data class ContainerQuantityBasedOFRResponse(
    val fulfillmentId: String,
    val ginNumber: String,
    val fulfillmentStatus: String,
    val summary: ContainerQuantityOFRSummary,
    val awbGenerated: Boolean,
    val awbNumber: String? = null,
    val awbPdf: String? = null,
    val trackingUrl: String? = null,
    val transactionsCreated: List<String>,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Summary of container-based OFR
 */
data class ContainerQuantityOFRSummary(
    val totalItemLines: Int,
    val totalUnits: Int,
    val totalPackages: Int,
    val totalContainerSourcesUsed: Int,
    val inventoryReductionsSummary: List<InventoryReductionSummary>
)

/**
 * Inventory reduction summary
 */
data class InventoryReductionSummary(
    val quantityInventoryId: String,
    val containerBarcode: String? = null,  // For Scenario 2
    val skuId: Long? = null,                // For Scenario 2
    val itemType: String? = null,           // For Scenario 3
    val quantityReduced: Int,
    val previousAvailable: Int,
    val newAvailable: Int,
    val locationReductions: List<LocationReductionSummary>? = null  // For Scenario 3
)

/**
 * Location reduction summary (Scenario 3)
 */
data class LocationReductionSummary(
    val locationCode: String,
    val quantityReduced: Int,
    val previousQuantity: Int,
    val newQuantity: Int
)

// ============================================
// API 2: Location-Based Quantity Picking (Scenario 3)
// ============================================

/**
 * Request DTO for creating OFR with location-based quantity picking
 * POST /api/v1/orders/fulfillment-requests/create-location-quantity-based
 */
data class CreateLocationQuantityBasedRequest(
    @field:NotNull(message = "Account ID is required")
    val accountId: Long,

    @field:NotNull(message = "Customer info is required")
    @field:Valid
    val customerInfo: CustomerInfoDto,

    @field:NotNull(message = "Shipping address is required")
    @field:Valid
    val shippingAddress: ShippingAddressDto,

    @field:NotEmpty(message = "At least one item must be picked")
    @field:Valid
    val itemsPicked: List<ItemPickedLocationBasedDto>,

    @field:NotEmpty(message = "At least one package must be provided")
    @field:Valid
    val packages: List<PackageLocationBasedDto>,

    @field:NotNull(message = "Shipping details are required")
    @field:Valid
    val shippingDetails: ShippingDetailsDto,

    val notes: String? = null,
    val tags: List<String> = listOf(),
    val customFields: Map<String, String> = mapOf()
)

/**
 * Item picked with location source information (Scenario 3)
 */
data class ItemPickedLocationBasedDto(
    @field:NotBlank(message = "Quantity inventory ID is required")
    val quantityInventoryId: String,

    @field:NotNull(message = "Item type is required")
    val itemType: String,  // PALLET or BOX only

    @field:NotNull(message = "Total quantity picked is required")
    @field:Min(value = 1, message = "Total quantity picked must be at least 1")
    val totalQuantityPicked: Int,

    val description: String? = null,

    @field:NotEmpty(message = "At least one source location must be provided")
    @field:Valid
    val sourceLocations: List<SourceLocationDto>
)

/**
 * Source location information (Scenario 3)
 */
data class SourceLocationDto(
    @field:NotBlank(message = "Location code is required")
    val locationCode: String,

    @field:NotNull(message = "Quantity picked is required")
    @field:Min(value = 1, message = "Quantity picked must be at least 1")
    val quantityPicked: Int
)

/**
 * Package for location-based outbound (Scenario 3)
 */
data class PackageLocationBasedDto(
    @field:NotBlank(message = "Package barcode is required")
    val packageBarcode: String,

    @field:NotNull(message = "Package dimensions are required")
    @field:Valid
    val dimensions: DimensionsDto,

    @field:NotNull(message = "Package weight is required")
    @field:Valid
    val weight: WeightDto,

    @field:NotEmpty(message = "Package items cannot be empty")
    @field:Valid
    val packagedItems: List<PackagedItemLocationBasedDto>
)

/**
 * Packaged item for location-based outbound (Scenario 3)
 */
data class PackagedItemLocationBasedDto(
    @field:NotBlank(message = "Quantity inventory ID is required")
    val quantityInventoryId: String,

    @field:NotNull(message = "Item type is required")
    val itemType: String,  // PALLET or BOX

    @field:NotNull(message = "Quantity is required")
    @field:Min(value = 1, message = "Quantity must be at least 1")
    val quantity: Int
)

/**
 * Response DTO for location-based OFR creation
 */
data class LocationQuantityBasedOFRResponse(
    val fulfillmentId: String,
    val ginNumber: String,
    val fulfillmentStatus: String,
    val summary: LocationQuantityOFRSummary,
    val awbGenerated: Boolean,
    val awbNumber: String? = null,
    val awbPdf: String? = null,
    val trackingUrl: String? = null,
    val transactionsCreated: List<String>,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Summary of location-based OFR
 */
data class LocationQuantityOFRSummary(
    val totalItemLines: Int,
    val totalUnits: Int,
    val totalPackages: Int,
    val totalLocationsUsed: Int,
    val inventoryReductionsSummary: List<InventoryReductionSummary>
)

// ============================================
// Common DTOs
// ============================================

/**
 * Package DTO (for Scenario 2)
 */
data class PackageDto(
    @field:NotBlank(message = "Package barcode is required")
    val packageBarcode: String,

    @field:NotNull(message = "Package dimensions are required")
    @field:Valid
    val dimensions: DimensionsDto,

    @field:NotNull(message = "Package weight is required")
    @field:Valid
    val weight: WeightDto,

    @field:NotEmpty(message = "Package items cannot be empty")
    @field:Valid
    val packagedItems: List<PackagedItemDto>
)

/**
 * Packaged item DTO (for Scenario 2)
 */
data class PackagedItemDto(
    @field:NotNull(message = "SKU ID is required")
    val skuId: Long,

    @field:NotNull(message = "Quantity is required")
    @field:Min(value = 1, message = "Quantity must be at least 1")
    val quantity: Int
)

/**
 * Package dimensions
 */
data class DimensionsDto(
    @field:NotNull(message = "Length is required")
    @field:Min(value = 0, message = "Length must be positive")
    val length: Double,

    @field:NotNull(message = "Width is required")
    @field:Min(value = 0, message = "Width must be positive")
    val width: Double,

    @field:NotNull(message = "Height is required")
    @field:Min(value = 0, message = "Height must be positive")
    val height: Double,

    @field:NotBlank(message = "Unit is required")
    val unit: String  // "cm" or "in"
)

/**
 * Package weight
 */
data class WeightDto(
    @field:NotNull(message = "Value is required")
    @field:Min(value = 0, message = "Weight must be positive")
    val value: Double,

    @field:NotBlank(message = "Unit is required")
    val unit: String  // "kg" or "lb"
)

