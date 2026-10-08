package com.wmspro.order.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty

/**
 * Items added to an outbound order after its GIN was created and before it is sent to the
 * customer. Each body carries only the new items and the packages they go out in - the order's
 * customer, address, GIN and existing contents are untouched. One body per kind of order, matching
 * how the order was created.
 */

/** Barcoded items, for an order created through `/direct`. */
data class AddDirectItemsRequest(
    @field:NotEmpty(message = "At least one package is required")
    @field:Valid
    val packages: List<DirectPackageDto>
)

/** Bulk pallets/boxes picked from locations, for an order created location-quantity-based. */
data class AddLocationQuantityItemsRequest(
    @field:NotEmpty(message = "At least one item must be picked")
    @field:Valid
    val itemsPicked: List<ItemPickedLocationBasedDto>,

    @field:NotEmpty(message = "At least one package is required")
    @field:Valid
    val packages: List<PackageLocationBasedDto>
)

/** SKU quantities picked from containers, for an order created container-quantity-based. */
data class AddContainerQuantityItemsRequest(
    @field:NotEmpty(message = "At least one item must be picked")
    @field:Valid
    val itemsPicked: List<ItemPickedContainerBasedDto>,

    @field:NotEmpty(message = "At least one package is required")
    @field:Valid
    val packages: List<PackageDto>
)

data class AddOfrItemsResponse(
    val fulfillmentId: String,
    val ginNumber: String,
    val addedItems: Int,
    val addedPackages: Int,
    /** Barcodes Inventory could not ship (e.g. already shipped); they were left off the GIN. */
    val skippedBarcodes: List<String> = emptyList(),
    val totalItems: Int,
    val totalPackages: Int
)
