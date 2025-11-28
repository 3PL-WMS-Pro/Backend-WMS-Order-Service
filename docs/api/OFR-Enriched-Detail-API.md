# OFR Enriched Detail API Documentation

## Overview

The **Enriched OFR Detail API** provides comprehensive Order Fulfillment Request details by aggregating data from multiple microservices into a single response. This eliminates the need for multiple API calls from the frontend and provides a complete view of the fulfillment order with all enriched data.

---

## API Endpoint

```
GET /api/v1/orders/fulfillment-requests/{fulfillmentId}/enriched
```

**Controller**: `OrderFulfillmentController.kt:186-216`
**Service**: `OfrEnrichmentService.kt`

---

## Request Structure

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `fulfillmentId` | String | Yes | The unique OFR identifier (e.g., "OFR-001") |

### Headers

| Header | Type | Required | Description |
|--------|------|----------|-------------|
| `Authorization` | String | Yes | Bearer token for authentication (required for fetching account and user names) |

### Example Request

```http
GET /api/v1/orders/fulfillment-requests/OFR-001/enriched
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## Response Structure

### Success Response (200 OK)

```json
{
  "success": true,
  "message": "Enriched OFR retrieved successfully with data from Product, Inventory, Account, and User services",
  "data": {
    // OfrEnrichedResponse object (see below)
  }
}
```

### Error Responses

#### 404 Not Found
```json
{
  "success": false,
  "message": "Order Fulfillment Request not found",
  "data": null
}
```

#### 500 Internal Server Error
```json
{
  "success": false,
  "message": "Failed to retrieve enriched OFR",
  "data": null
}
```

---

## Data Enrichment

This API enriches OFR data by fetching information from the following microservices:

| Microservice | Data Enriched | Fields Added |
|--------------|---------------|--------------|
| **Product Service** | SKU Details | `skuCode`, `productTitle`, `productImageUrl` |
| **Inventory Service** | Storage Item Details | `itemBarcode`, `lengthCm`, `widthCm`, `heightCm`, `volumeCbm`, `weightKg`, `parentItemBarcode` |
| **Inventory Service** | Quantity Inventory Details | `itemType`, `totalQuantity`, `availableQuantity`, `description` |
| **Account Service** | Account Names | `accountName` |
| **User Service** | User Names | `createdByUserName`, `updatedByUserName` |

**Note**: Task Service data is intentionally excluded from this API.

---

## Complete Response Schema

### OfrEnrichedResponse

```typescript
{
  // ========== BASIC OFR INFORMATION ==========
  fulfillmentId: string,              // e.g., "OFR-001"
  accountId: number,                  // e.g., 123
  accountName: string | null,         // ENRICHED - e.g., "ABC Corporation"
  fulfillmentSource: FulfillmentSource,
  fulfillmentType: FulfillmentType,
  externalOrderId: string | null,     // Client's original order ID
  externalOrderNumber: string | null, // Client's order number
  fulfillmentStatus: FulfillmentStatus,
  priority: Priority,
  executionApproach: ExecutionApproach,

  // ========== QC DETAILS ==========
  qcDetails: {
    qcInspectionId: string | null,
    qcCompletedAt: string | null,     // ISO 8601 datetime
    qcCompletedBy: string | null
  } | null,

  // ========== TASK IDS ==========
  pickingTaskId: string | null,
  packMoveTaskId: string | null,
  pickPackMoveTaskId: string | null,
  loadingTaskId: string | null,

  // ========== CUSTOMER INFORMATION ==========
  customerInfo: {
    name: string,
    email: string,
    phone: string | null
  },

  // ========== SHIPPING ADDRESS ==========
  shippingAddress: {
    name: string | null,
    company: string | null,
    addressLine1: string,
    addressLine2: string | null,
    city: string,
    state: string | null,
    country: string,
    postalCode: string | null,
    phone: string | null
  },

  // ========== ENRICHED LINE ITEMS ==========
  lineItems: LineItemEnriched[],      // See LineItemEnriched schema below

  // ========== ENRICHED PACKAGES ==========
  packages: PackageEnriched[],        // See PackageEnriched schema below

  // ========== QUANTITY SOURCE DETAILS ==========
  quantitySourceDetails: {            // See QuantitySourceDetailsEnriched below
    itemSources: ItemSourceEnriched[]
  } | null,

  // ========== SHIPPING DETAILS ==========
  shippingDetails: {
    awbCondition: AwbCondition,
    carrier: string | null,
    requestedServiceType: ServiceType | null,
    selectedServiceCode: string | null,
    shipmentId: string | null,
    awbNumber: string | null,
    awbPdf: string | null,            // Base64 encoded PDF
    trackingUrl: string | null,
    shippingLabelPdf: string | null
  } | null,

  // ========== STATUS TRACKING ==========
  statusHistory: [
    {
      status: FulfillmentStatus,
      timestamp: string,              // ISO 8601 datetime
      user: string | null,
      automated: boolean,
      notes: string | null,
      currentStatus: boolean
    }
  ],

  fulfillmentRequestDate: string,     // ISO 8601 datetime

  // ========== FINANCIAL SUMMARY ==========
  orderValue: {
    subtotal: number | null,
    shipping: number | null,
    tax: number | null,
    total: number | null,
    currency: string                  // Default: "AED"
  } | null,

  // ========== GIN MANAGEMENT ==========
  ginNumber: string | null,           // e.g., "GIN-001"
  ginNotification: {
    sentToCustomer: boolean,
    sentAt: string | null,            // ISO 8601 datetime
    ginDate: string | null,           // ISO 8601 datetime
    toEmail: string | null,
    ccEmails: string[],
    subject: string | null,
    emailContent: string | null,
    signedGINCopy: string | null,
    attachments: [
      {
        fileName: string,
        fileUrl: string
      }
    ]
  } | null,

  // ========== LOADING DOCUMENTS ==========
  loadingDocuments: {
    packagePhotosUrls: string[],
    truckDriverPhotoUrl: string | null,
    truckDriverIdProofUrl: string | null
  } | null,

  // ========== METADATA ==========
  notes: string | null,
  tags: string[],
  customFields: { [key: string]: any },

  // ========== CANCELLATION ==========
  cancelledAt: string | null,         // ISO 8601 datetime
  cancellationReason: string | null,

  // ========== AUDIT FIELDS (ENRICHED) ==========
  createdBy: string | null,           // User ID
  createdByUserName: string | null,   // ENRICHED - Full name
  updatedBy: string | null,           // User ID
  updatedByUserName: string | null,   // ENRICHED - Full name
  createdAt: string,                  // ISO 8601 datetime
  updatedAt: string                   // ISO 8601 datetime
}
```

---

### LineItemEnriched Schema

```typescript
{
  lineItemId: string,                 // e.g., "LI-001"
  itemType: ItemType,                 // "SKU_ITEM" | "BOX" | "PALLET"

  // ========== SKU INFORMATION (ENRICHED) ==========
  skuId: number | null,
  skuCode: string | null,             // ENRICHED from Product Service
  productTitle: string | null,        // ENRICHED from Product Service
  productImageUrl: string | null,     // ENRICHED from Product Service (primary image URL)

  itemBarcode: string | null,
  allocationMethod: AllocationMethod | null, // "FIFO" | "LIFO" | "RANDOM"

  // ========== QUANTITIES ==========
  quantityOrdered: number,
  quantityPicked: number,
  quantityShipped: number,

  // ========== ENRICHED ALLOCATED ITEMS ==========
  // (Used for STANDARD_TASK_BASED fulfillment)
  allocatedItems: [
    {
      storageItemId: number,
      location: string,               // e.g., "A-01-R1-S3-B4"

      // ENRICHED Storage Item Details from Inventory Service
      itemBarcode: string | null,
      itemType: string | null,
      skuId: number | null,
      lengthCm: number | null,
      widthCm: number | null,
      heightCm: number | null,
      volumeCbm: number | null,
      weightKg: number | null,
      parentItemBarcode: string | null,

      // Picking Tracking
      picked: boolean,
      pickedAt: string | null,        // ISO 8601 datetime
      pickedBy: string | null
    }
  ],

  // ========== ENRICHED QUANTITY INVENTORY REFERENCES ==========
  // (Used for CONTAINER_QUANTITY_BASED and LOCATION_QUANTITY_BASED fulfillment)
  quantityInventoryReferences: [
    {
      quantityInventoryId: string,    // e.g., "QBI-001"
      quantityShipped: number,
      containerBarcode: string | null,
      locationCode: string | null,
      transactionId: string,

      // ENRICHED Quantity Inventory Details from Inventory Service
      itemType: string | null,
      totalQuantity: number | null,
      availableQuantity: number | null,
      description: string | null
    }
  ]
}
```

---

### PackageEnriched Schema

```typescript
{
  packageId: string,                  // e.g., "PKG-001"
  packageBarcode: string | null,

  // ========== PACKAGE PHYSICAL DETAILS ==========
  dimensions: {
    length: number,
    width: number,
    height: number,
    unit: string                      // Default: "cm"
  } | null,

  weight: {
    value: number,
    unit: string                      // Default: "kg"
  } | null,

  // ========== ENRICHED ASSIGNED ITEMS ==========
  assignedItems: [
    {
      storageItemId: number,
      skuId: number | null,
      itemType: ItemType,             // "SKU_ITEM" | "BOX" | "PALLET"
      itemBarcode: string,

      // ENRICHED SKU Details from Product Service
      skuCode: string | null,
      productTitle: string | null,
      productImageUrl: string | null,

      // ENRICHED Storage Item Details from Inventory Service
      lengthCm: number | null,
      widthCm: number | null,
      heightCm: number | null,
      volumeCbm: number | null,
      weightKg: number | null
    }
  ],

  // ========== DISPATCH TRACKING ==========
  dispatchArea: string | null,
  dispatchAreaBarcode: string | null,
  droppedAtDispatch: boolean,
  dispatchScannedAt: string | null,   // ISO 8601 datetime

  // ========== PACKAGE LIFECYCLE ==========
  createdAt: string,                  // ISO 8601 datetime
  createdByTask: string | null,
  savedAt: string | null,             // ISO 8601 datetime

  // ========== SHIPPING DETAILS ==========
  loadedOnTruck: boolean,
  truckNumber: string | null,
  loadedAt: string | null             // ISO 8601 datetime
}
```

---

### QuantitySourceDetailsEnriched Schema

```typescript
{
  itemSources: [
    {
      // For CONTAINER_QUANTITY_BASED (Scenario 2)
      skuId: number | null,
      skuCode: string | null,         // ENRICHED from Product Service
      productTitle: string | null,    // ENRICHED from Product Service
      totalQuantityPicked: number | null,

      // For LOCATION_QUANTITY_BASED (Scenario 3)
      quantityInventoryId: string | null,
      itemType: ItemType | null,
      description: string | null,

      // Container Sources (Scenario 2)
      containerSources: [
        {
          containerBarcode: string,
          quantityInventoryId: string,
          quantityPicked: number,
          locationCode: string,

          // ENRICHED Quantity Inventory Details
          itemType: string | null,
          totalQuantity: number | null,
          availableQuantity: number | null,
          description: string | null
        }
      ] | null,

      // Location Sources (Scenario 3)
      locationSources: [
        {
          locationCode: string,
          quantityPicked: number
        }
      ] | null
    }
  ]
}
```

---

## Enums Reference

### FulfillmentStatus

```typescript
enum FulfillmentStatus {
  RECEIVED       // Initial status when OFR is created
  ALLOCATED      // Inventory allocated and task created
  PICKING        // Picking task in progress
  PICKED         // All items picked
  PACKING        // Packing in progress
  PACKED         // All items packed
  READY_TO_SHIP  // Ready for shipping
  SHIPPED        // Shipped to customer
  DELIVERED      // Delivered to customer
  CANCELLED      // Cancelled by user/system
  ON_HOLD        // On hold for some reason
}
```

### Priority

```typescript
enum Priority {
  URGENT
  STANDARD
  ECONOMY
}
```

### ExecutionApproach

```typescript
enum ExecutionApproach {
  SEPARATED_PICKING         // Separate PICKING and PACK_MOVE tasks
  PICK_PACK_MOVE_TOGETHER   // Combined PICK_PACK_MOVE task
  DIRECT_PROCESSING         // Direct processing without tasks (Web-based express fulfillment)
}
```

### FulfillmentSource

```typescript
enum FulfillmentSource {
  SHOPIFY
  MANUAL
  API
  EMAIL
  PHONE
  WEB_PORTAL  // Web-based direct processing
}
```

### FulfillmentType

```typescript
enum FulfillmentType {
  STANDARD_TASK_BASED        // Scenario 1: Task-based with individual barcodes
  CONTAINER_QUANTITY_BASED   // Scenario 2: Container-based quantity tracking
  LOCATION_QUANTITY_BASED    // Scenario 3: Location-based quantity tracking
}
```

**Details**:
- **STANDARD_TASK_BASED**: Traditional fulfillment with tasks and mobile app
  - System allocates StorageItems automatically (FIFO/LIFO/RANDOM)
  - Creates PICKING/PACK_MOVE tasks
  - Worker uses mobile app to scan individual barcodes

- **CONTAINER_QUANTITY_BASED**: Direct recording with container sources
  - Containers (boxes/pallets) have barcodes
  - SKU contents tracked by quantity (no individual SKU barcodes)
  - Executive manually picks and records on web app
  - No tasks created

- **LOCATION_QUANTITY_BASED**: Direct recording with location sources
  - No barcodes, pure quantity tracking
  - Bulk items (pallets/boxes) picked from physical locations
  - Executive manually picks and records on web app
  - No tasks created

### ItemType

```typescript
enum ItemType {
  SKU_ITEM
  BOX
  PALLET
}
```

### AllocationMethod

```typescript
enum AllocationMethod {
  FIFO    // First In First Out - oldest items first
  LIFO    // Last In First Out - newest items first
  RANDOM  // No specific order
}
```

---

## Example Response

```json
{
  "success": true,
  "message": "Enriched OFR retrieved successfully with data from Product, Inventory, Account, and User services",
  "data": {
    "fulfillmentId": "OFR-001",
    "accountId": 123,
    "accountName": "ABC Corporation",
    "fulfillmentSource": "WEB_PORTAL",
    "fulfillmentType": "STANDARD_TASK_BASED",
    "externalOrderId": "EXT-12345",
    "externalOrderNumber": "ORD-2024-001",
    "fulfillmentStatus": "PICKING",
    "priority": "URGENT",
    "executionApproach": "SEPARATED_PICKING",

    "qcDetails": null,

    "pickingTaskId": "TSK-PICK-001",
    "packMoveTaskId": null,
    "pickPackMoveTaskId": null,
    "loadingTaskId": null,

    "customerInfo": {
      "name": "John Doe",
      "email": "john.doe@example.com",
      "phone": "+971501234567"
    },

    "shippingAddress": {
      "name": "John Doe",
      "company": "Acme Inc.",
      "addressLine1": "123 Business Bay",
      "addressLine2": "Tower A, Floor 5",
      "city": "Dubai",
      "state": "Dubai",
      "country": "UAE",
      "postalCode": "12345",
      "phone": "+971501234567"
    },

    "lineItems": [
      {
        "lineItemId": "LI-001",
        "itemType": "SKU_ITEM",
        "skuId": 456,
        "skuCode": "SKU-LAPTOP-001",
        "productTitle": "Dell Latitude 5520 Laptop",
        "productImageUrl": "https://cdn.example.com/products/laptop-001.jpg",
        "itemBarcode": null,
        "allocationMethod": "FIFO",
        "quantityOrdered": 2,
        "quantityPicked": 1,
        "quantityShipped": 0,
        "allocatedItems": [
          {
            "storageItemId": 789,
            "location": "A-01-R1-S3-B4",
            "itemBarcode": "BAR-LAPTOP-001",
            "itemType": "SKU_ITEM",
            "skuId": 456,
            "lengthCm": 35.5,
            "widthCm": 25.0,
            "heightCm": 3.0,
            "volumeCbm": 0.026625,
            "weightKg": 2.5,
            "parentItemBarcode": null,
            "picked": true,
            "pickedAt": "2024-01-15T10:30:00",
            "pickedBy": "USR-001"
          },
          {
            "storageItemId": 790,
            "location": "A-01-R1-S3-B5",
            "itemBarcode": "BAR-LAPTOP-002",
            "itemType": "SKU_ITEM",
            "skuId": 456,
            "lengthCm": 35.5,
            "widthCm": 25.0,
            "heightCm": 3.0,
            "volumeCbm": 0.026625,
            "weightKg": 2.5,
            "parentItemBarcode": null,
            "picked": false,
            "pickedAt": null,
            "pickedBy": null
          }
        ],
        "quantityInventoryReferences": []
      }
    ],

    "packages": [
      {
        "packageId": "PKG-001",
        "packageBarcode": "PKG-BAR-001",
        "dimensions": {
          "length": 40.0,
          "width": 30.0,
          "height": 20.0,
          "unit": "cm"
        },
        "weight": {
          "value": 3.5,
          "unit": "kg"
        },
        "assignedItems": [
          {
            "storageItemId": 789,
            "skuId": 456,
            "itemType": "SKU_ITEM",
            "itemBarcode": "BAR-LAPTOP-001",
            "skuCode": "SKU-LAPTOP-001",
            "productTitle": "Dell Latitude 5520 Laptop",
            "productImageUrl": "https://cdn.example.com/products/laptop-001.jpg",
            "lengthCm": 35.5,
            "widthCm": 25.0,
            "heightCm": 3.0,
            "volumeCbm": 0.026625,
            "weightKg": 2.5
          }
        ],
        "dispatchArea": "DISPATCH-A",
        "dispatchAreaBarcode": "DISP-A-001",
        "droppedAtDispatch": true,
        "dispatchScannedAt": "2024-01-15T11:00:00",
        "createdAt": "2024-01-15T10:45:00",
        "createdByTask": "TSK-PACK-001",
        "savedAt": "2024-01-15T10:50:00",
        "loadedOnTruck": false,
        "truckNumber": null,
        "loadedAt": null
      }
    ],

    "quantitySourceDetails": null,

    "shippingDetails": {
      "awbCondition": "CREATE_FOR_CUSTOMER",
      "carrier": "DHL",
      "requestedServiceType": "EXPRESS",
      "selectedServiceCode": "DHL-EXP-001",
      "shipmentId": null,
      "awbNumber": null,
      "awbPdf": null,
      "trackingUrl": null,
      "shippingLabelPdf": null
    },

    "statusHistory": [
      {
        "status": "RECEIVED",
        "timestamp": "2024-01-15T09:00:00",
        "user": "USR-001",
        "automated": false,
        "notes": "OFR created",
        "currentStatus": false
      },
      {
        "status": "ALLOCATED",
        "timestamp": "2024-01-15T09:05:00",
        "user": null,
        "automated": true,
        "notes": "Inventory allocated automatically",
        "currentStatus": false
      },
      {
        "status": "PICKING",
        "timestamp": "2024-01-15T10:00:00",
        "user": "USR-002",
        "automated": false,
        "notes": "Picking started",
        "currentStatus": true
      }
    ],

    "fulfillmentRequestDate": "2024-01-15T09:00:00",

    "orderValue": {
      "subtotal": 2000.00,
      "shipping": 50.00,
      "tax": 100.00,
      "total": 2150.00,
      "currency": "AED"
    },

    "ginNumber": null,
    "ginNotification": null,
    "loadingDocuments": null,

    "notes": "High priority order - customer VIP",
    "tags": ["VIP", "URGENT", "FRAGILE"],
    "customFields": {
      "salesRep": "John Smith",
      "specialInstructions": "Handle with care"
    },

    "cancelledAt": null,
    "cancellationReason": null,

    "createdBy": "USR-001",
    "createdByUserName": "Alice Johnson",
    "updatedBy": "USR-002",
    "updatedByUserName": "Bob Williams",
    "createdAt": "2024-01-15T09:00:00",
    "updatedAt": "2024-01-15T10:30:00"
  }
}
```

---

## Error Handling

The API implements graceful degradation for data enrichment:

### SKU Data Errors
If Product Service fails or SKU is not found:
- `skuCode`: Returns `"N/A (ERR)"` or `"N/A"`
- `productTitle`: Returns `"N/A (ERR)"` or `"N/A"`
- `productImageUrl`: Returns `null`

### Storage Item Data Errors
If Inventory Service fails or Storage Item is not found:
- All enriched fields return `null`
- Base OFR data is still returned

### Account/User Name Errors
If Account or User Service fails:
- `accountName`: Returns `null`
- `createdByUserName`: Returns `null`
- `updatedByUserName`: Returns `null`

### Base OFR Not Found
- Returns **404 Not Found**
- No partial data is returned

---

## Performance Characteristics

### Batch API Calls
The service makes exactly **5 external API calls** regardless of data size:
1. Product Service - Batch SKU details
2. Inventory Service - Batch Storage Item details
3. Inventory Service - Batch Quantity Inventory details
4. Account Service - Single account name
5. User Service - Batch user names

### Optimization
- All IDs are collected in a single pass through the OFR data
- External services are called with batch requests
- Empty ID sets skip API calls entirely
- Parallel processing of independent service calls

---

## Use Cases

### 1. OFR Detail Page Display
Fetch complete OFR information including product images, dimensions, and user names for display on a detail page.

### 2. Package Inspection
View all packages with their assigned items, including product details and physical dimensions.

### 3. Order Timeline
Display status history with enriched user names for audit trail.

### 4. Fulfillment Progress Tracking
Track picking progress with allocated items, their locations, and pick status.

### 5. Shipping Preparation
View complete package information with weights, dimensions, and dispatch status.

---

## Frontend Integration Notes

### 1. Loading States
Since this API aggregates data from 5+ services, expect response times of 500ms - 2s. Implement proper loading indicators.

### 2. Image Handling
- `productImageUrl` may be `null` - always provide fallback images
- URLs are absolute paths to CDN/storage

### 3. Null Handling
Many fields are nullable - implement proper null checks:
- Enriched data may be `null` if services fail
- Optional fields like `qcDetails`, `packages`, `ginNotification` may be `null`

### 4. DateTime Format
All datetime fields are in **ISO 8601 format** (e.g., `"2024-01-15T10:30:00"`)
- Parse using appropriate date libraries (moment.js, date-fns, etc.)

### 5. Conditional Rendering
Based on `fulfillmentType`:
- **STANDARD_TASK_BASED**: Show `allocatedItems` in line items
- **CONTAINER_QUANTITY_BASED / LOCATION_QUANTITY_BASED**: Show `quantityInventoryReferences` and `quantitySourceDetails`

### 6. Status-Based UI
Use `fulfillmentStatus` to determine which sections to display:
- Show packages only if status is `PACKED` or later
- Show GIN details only if `ginNumber` exists
- Show loading documents only if status is `SHIPPED`

### 7. Caching Considerations
- This endpoint returns a snapshot of the OFR at request time
- If OFR is being actively worked on, data may become stale
- Consider implementing periodic refresh or websocket updates

---

## Testing

### Test Scenarios

1. **Standard Task-Based OFR**
   - `fulfillmentType: STANDARD_TASK_BASED`
   - Verify `allocatedItems` are populated and enriched
   - Verify `quantitySourceDetails` is `null`

2. **Container Quantity-Based OFR**
   - `fulfillmentType: CONTAINER_QUANTITY_BASED`
   - Verify `quantitySourceDetails` is populated
   - Verify `containerSources` have enriched QBI data

3. **With Packages**
   - Verify `packages` array has enriched `assignedItems`
   - Verify product images and dimensions are present

4. **With GIN**
   - Verify `ginNumber` and `ginNotification` are populated
   - Check `attachments` array structure

5. **Error Handling**
   - Test with invalid `fulfillmentId` (should return 404)
   - Test without Authorization header (may fail to enrich account/user names)

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2024-01-15 | Initial API implementation with multi-service enrichment |

---

## Support & Contact

For issues or questions about this API:
- Backend Service: `OfrEnrichmentService.kt`
- Controller: `OrderFulfillmentController.kt:186-216`
- DTOs: `OfrEnrichedDtos.kt`
