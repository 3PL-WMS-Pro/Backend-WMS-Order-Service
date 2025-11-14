package com.wmspro.order.enums

/**
 * Enum to distinguish between different fulfillment types
 *
 * STANDARD_TASK_BASED: Traditional fulfillment with tasks and mobile app (Scenario 1)
 *   - System allocates StorageItems automatically (FIFO/LIFO/RANDOM)
 *   - Creates PICKING/PACK_MOVE tasks
 *   - Worker uses mobile app to scan individual barcodes
 *
 * CONTAINER_QUANTITY_BASED: Direct recording with container sources (Scenario 2)
 *   - Containers (boxes/pallets) have barcodes
 *   - SKU contents tracked by quantity (no individual SKU barcodes)
 *   - Executive manually picks and records on web app
 *   - No tasks created
 *
 * LOCATION_QUANTITY_BASED: Direct recording with location sources (Scenario 3)
 *   - No barcodes, pure quantity tracking
 *   - Bulk items (pallets/boxes) picked from physical locations
 *   - Executive manually picks and records on web app
 *   - No tasks created
 */
enum class FulfillmentType {
    STANDARD_TASK_BASED,           // Scenario 1: Task-based with individual barcodes
    CONTAINER_QUANTITY_BASED,      // Scenario 2: Container-based quantity tracking
    LOCATION_QUANTITY_BASED        // Scenario 3: Location-based quantity tracking
}
