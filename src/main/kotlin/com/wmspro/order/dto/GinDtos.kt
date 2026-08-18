package com.wmspro.order.dto

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Data Transfer Object for GIN (Goods Issue Note) generation
 * Contains all data needed to populate the GIN template
 * Note: Uses ShippingAddressDto and OrderValueDto from OrderDtos.kt
 */
data class GinDataDto(
    // Header Information
    val owner: String, // Account/Customer name
    val company: String, // Warehouse owner company name (tenant name)
    val ginNumber: String, // GIN-001
    val fulfillmentRequestId: String, // OFR-001
    val externalOrderNumber: String? = null, // Client's order number
    val clientReferenceNum: String? = null, // Client's reference number
    val customerRef: String? = null,
    val warehouseRef: String? = null,
    val dateIssued: LocalDateTime,
    val transactionDate: LocalDateTime,
    val status: String,

    // Shipping Information
    val carrier: String? = null,
    val awbNumber: String? = null,
    val trackingUrl: String? = null,
    val serviceType: String? = null,

    // Shipping Address (using existing DTO)
    val shippingAddress: ShippingAddressDto? = null,

    // Customer Information
    val customerName: String? = null,
    val customerEmail: String? = null,
    val customerPhone: String? = null,

    // Items Table
    val items: List<GinItemDto>,

    // Summary
    val totalOrdered: Int = 0,
    val totalPicked: Int = 0,
    val totalShipped: Int = 0,
    val totalCBM: Double = 0.0,
    val totalWeight: Double = 0.0,

    // Order Value (using existing DTO)
    val orderValue: OrderValueDto? = null,

    // Additional metadata
    val generatedAt: LocalDateTime = LocalDateTime.now(),
    val generatedBy: String? = null
)

/**
 * Individual item in GIN table
 */
data class GinItemDto(
    val itemCode: String, // SKU Code, BOX-001, PLT-001
    val description: String, // Product title or item description
    val itemType: String, // SKU_ITEM, BOX, PALLET
    val quantityOrdered: Int,
    val quantityPicked: Int,
    val quantityShipped: Int,
    val length: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val cbm: Double? = null,
    val weight: Double? = null,
    val unit: String = "cm" // cm, inches, etc.
)

/**
 * Request DTO for sending GIN email
 */
data class SendGinRequest(
    val toEmail: String,
    val ccEmails: List<String> = emptyList(),
    val subject: String,
    val emailContent: String,
    val emailConfigKey: String = "default" // Which email config to use
)

/**
 * Request DTO for recording a GIN as sent WITHOUT the application emailing it.
 *
 * Deliberately not a flag on [SendGinRequest]: that DTO requires `toEmail`, `subject` and
 * `emailContent`, none of which exist here, and reusing it would leave the audit trail unable
 * to tell an emailed GIN from a manually recorded one afterwards.
 *
 * @param ginDate Business date to record on the GIN document. Defaults to today when absent.
 * @param note Optional free text explaining how the document actually reached the customer.
 */
data class MarkGinSentRequest(
    val ginDate: LocalDate? = null,
    val note: String? = null
)

/**
 * Response DTO for email template
 */
data class GinEmailTemplateResponse(
    val subject: String,
    val body: String,
    val emailConfigKey: String = "default",
    val ccEmails: List<String> = emptyList(),
    val bccEmails: List<String> = emptyList()
)

/**
 * Request DTO for adding or updating GIN attachment
 */
data class AddGinAttachmentRequest(
    val fileName: String,
    val fileUrl: String
)

/**
 * Request DTO for updating GIN date and signed GIN copy
 * Both fields are optional - if null, the existing value in DB will be preserved
 */
data class UpdateGinDetailsRequest(
    val ginDate: LocalDateTime? = null,
    val signedGinCopy: String? = null
)
