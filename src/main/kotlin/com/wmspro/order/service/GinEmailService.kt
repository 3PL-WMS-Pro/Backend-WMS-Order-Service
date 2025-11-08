package com.wmspro.order.service

import com.wmspro.common.tenant.TenantContext
import com.wmspro.order.client.EmailConfig
import com.wmspro.order.client.TenantServiceClient
import com.wmspro.order.dto.SendGinRequest
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import java.util.*

/**
 * Service for sending GIN emails with PDF attachments
 */
@Service
class GinEmailService(
    private val tenantServiceClient: TenantServiceClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Send GIN email with PDF attachment
     *
     * @param request Email sending request
     * @param pdfBytes GIN PDF as byte array
     * @param ginNumber GIN number for filename
     */
    fun sendGinEmail(
        request: SendGinRequest,
        pdfBytes: ByteArray,
        ginNumber: String
    ) {
        logger.info("Sending GIN email to: {} using email config key: {}", request.toEmail, request.emailConfigKey)

        // 1. Fetch email configuration from tenant service using the specified config key
        val emailConfig = fetchEmailConfig(request.emailConfigKey)
            ?: throw IllegalStateException("Email configuration '${request.emailConfigKey}' not found. Please configure email settings first.")

        // 2. Create mail sender with tenant's SMTP settings
        val mailSender = createMailSender(emailConfig)

        // 3. Create and send email
        val message = createMimeMessage(mailSender, emailConfig, request, pdfBytes, ginNumber)

        try {
            mailSender.send(message)
            logger.info("GIN email sent successfully to: {} using config: {}", request.toEmail, request.emailConfigKey)
        } catch (e: Exception) {
            logger.error("Failed to send GIN email", e)
            throw RuntimeException("Failed to send GIN email: ${e.message}", e)
        }
    }

    /**
     * Fetch email configuration from tenant service by config key
     */
    private fun fetchEmailConfig(emailConfigKey: String = "default"): EmailConfig? {
        val tenantId = TenantContext.getCurrentTenant() ?: throw IllegalStateException("Tenant context not available")

        return try {
            val response = tenantServiceClient.getTenantByClientId(tenantId.toInt(), true)
            val data = response.data
            if (response.success && data != null) {
                val emailConfigs = data.tenantSettings?.emailConfigs ?: emptyMap()
                emailConfigs[emailConfigKey] ?: run {
                    logger.warn("Email config with key '{}' not found, trying 'default'", emailConfigKey)
                    emailConfigs["default"]
                }
            } else {
                logger.warn("Failed to fetch email config from tenant service")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching email config", e)
            null
        }
    }

    /**
     * Create JavaMailSender with tenant's SMTP configuration
     */
    private fun createMailSender(emailConfig: EmailConfig): JavaMailSenderImpl {
        val mailSender = JavaMailSenderImpl()

        mailSender.host = emailConfig.smtpHost
        mailSender.port = emailConfig.smtpPort
        mailSender.username = emailConfig.username
        mailSender.password = emailConfig.password

        val props = Properties()
        if (emailConfig.authEnabled) {
            props["mail.smtp.auth"] = "true"
        }
        if (emailConfig.useTLS) {
            props["mail.smtp.starttls.enable"] = "true"
        }
        if (emailConfig.useSSL) {
            props["mail.smtp.ssl.enable"] = "true"
        }
        props["mail.smtp.timeout"] = "10000"
        props["mail.smtp.connectiontimeout"] = "10000"

        mailSender.javaMailProperties = props

        return mailSender
    }

    /**
     * Create MIME message with PDF attachment
     */
    private fun createMimeMessage(
        mailSender: JavaMailSenderImpl,
        emailConfig: EmailConfig,
        request: SendGinRequest,
        pdfBytes: ByteArray,
        ginNumber: String
    ): MimeMessage {
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")

        // Set sender
        helper.setFrom(emailConfig.fromEmail, emailConfig.fromName ?: emailConfig.fromEmail)

        // Set recipient
        helper.setTo(request.toEmail)

        // Set CC if provided
        if (request.ccEmails.isNotEmpty()) {
            helper.setCc(request.ccEmails.toTypedArray())
        }

        // Set subject
        helper.setSubject(request.subject)

        // Set email body (HTML)
        helper.setText(request.emailContent, true)

        // Add PDF attachment
        val filename = "GIN_${ginNumber}.pdf"
        helper.addAttachment(filename, org.springframework.core.io.ByteArrayResource(pdfBytes))

        return message
    }

    /**
     * Get default GIN email template content
     */
    fun getDefaultGinEmailTemplate(ginNumber: String, customerName: String): com.wmspro.order.dto.GinEmailTemplateResponse {
        val tenantId = TenantContext.getCurrentTenant() ?: throw IllegalStateException("Tenant context not available")

        // Try to fetch custom template from tenant settings
        try {
            val response = tenantServiceClient.getTenantByClientId(tenantId.toInt(), true)
            val data = response.data
            if (response.success && data != null) {
                val emailTemplate = data.tenantSettings?.emailTemplates?.ginEmail
                if (emailTemplate != null) {
                    return com.wmspro.order.dto.GinEmailTemplateResponse(
                        subject = emailTemplate.subject,
                        body = emailTemplate.body,
                        emailConfigKey = emailTemplate.emailConfigKey,
                        ccEmails = emailTemplate.ccEmails,
                        bccEmails = emailTemplate.bccEmails
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch custom email template, using default", e)
        }

        // Fallback to default template
        val subject = "GIN $ginNumber - Goods Issue Note"
        val body = """
            <html>
            <body>
                <p>Dear $customerName,</p>

                <p>Please find attached the Goods Issue Note (GIN) for GIN number <strong>$ginNumber</strong>.</p>

                <p>This document confirms the issue of goods from our warehouse facility.</p>

                <p>If you have any questions or concerns regarding this shipment, please do not hesitate to contact us.</p>

                <br/>
                <p>Best regards,<br/>
                Warehouse Operations Team</p>
            </body>
            </html>
        """.trimIndent()

        return com.wmspro.order.dto.GinEmailTemplateResponse(
            subject = subject,
            body = body,
            emailConfigKey = "default"
        )
    }
}
