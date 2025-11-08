package com.wmspro.order.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.wmspro.common.dto.ApiResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDateTime

/**
 * Feign Client for Tenant Service
 */
@FeignClient(
    name = "wms-tenant-service",
    url = "\${services.tenant.url:http://localhost:6010}"
)
interface TenantServiceClient {

    @GetMapping("/api/v1/document-templates/active/{documentType}")
    fun getActiveDocumentTemplate(
        @PathVariable documentType: String
    ): ApiResponse<DocumentTemplateResponse>

    @GetMapping("/api/v1/tenants/client/{clientId}")
    fun getTenantByClientId(
        @PathVariable clientId: Int,
        @RequestParam(required = false, defaultValue = "false") includeSettings: Boolean
    ): ApiResponse<TenantInfoResponse>
}

/**
 * Document Template Response
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DocumentTemplateResponse(
    val templateId: String?,
    val tenantId: Int? = null,
    val documentType: String,
    val templateName: String,
    val templateVersion: String,
    val htmlTemplate: String,
    val cssContent: String?,
    val commonConfig: CommonConfig,
    val documentConfig: Map<String, Any>,
    val isActive: Boolean,
    val isDefault: Boolean,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommonConfig(
    val logoUrl: String? = null,
    val companyName: String? = null,
    val primaryColor: String = "#000000",
    val secondaryColor: String = "#666666",
    val fontFamily: String = "Arial, sans-serif",
    val pageSize: String = "A4",
    val orientation: String = "portrait",
    val margins: PageMargins = PageMargins()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PageMargins(
    val top: String = "20mm",
    val right: String = "15mm",
    val bottom: String = "20mm",
    val left: String = "15mm"
)

/**
 * Tenant Info Response (from GET /api/v1/tenants/client/{clientId})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TenantInfoResponse(
    val clientId: Int,
    val tenantName: String,
    val status: String,
    val databaseName: String,
    val tenantSettings: TenantSettings?,
    val connectionHealth: String? = null,
    val lastConnected: LocalDateTime? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TenantSettings(
    val emailConfigs: Map<String, EmailConfig> = emptyMap(),
    val emailTemplates: EmailTemplates? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmailConfig(
    val smtpHost: String,
    val smtpPort: Int = 587,
    val username: String,
    val password: String,
    val fromEmail: String,
    val fromName: String? = null,
    val useTLS: Boolean = true,
    val useSSL: Boolean = false,
    val authEnabled: Boolean = true
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmailTemplates(
    val grnEmail: EmailTemplate? = null,
    val ginEmail: EmailTemplate? = null,
    val invoiceEmail: EmailTemplate? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmailTemplate(
    val subject: String,
    val body: String,
    val emailConfigKey: String = "default",
    val ccEmails: List<String> = emptyList(),
    val bccEmails: List<String> = emptyList()
)
