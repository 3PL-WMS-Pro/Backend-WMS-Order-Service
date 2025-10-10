package com.wmspro.order.enums

enum class FulfillmentStatus {
    RECEIVED,           // Initial status when OFR is created
    ALLOCATED,          // Inventory allocated and task created
    PICKING,            // Picking task in progress
    PICKED,             // All items picked
    PACKING,            // Packing in progress
    PACKED,             // All items packed
    READY_TO_SHIP,      // Ready for shipping
    SHIPPED,            // Shipped to customer
    DELIVERED,          // Delivered to customer
    CANCELLED,          // Cancelled by user/system
    ON_HOLD             // On hold for some reason
}
