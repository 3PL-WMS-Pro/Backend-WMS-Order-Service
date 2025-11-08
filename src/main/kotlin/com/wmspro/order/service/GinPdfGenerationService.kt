package com.wmspro.order.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.wmspro.order.client.DocumentTemplateResponse
import com.wmspro.order.client.TenantServiceClient
import com.wmspro.order.dto.GinDataDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.StringTemplateResolver

/**
 * Service for generating GIN PDFs from templates
 */
@Service
class GinPdfGenerationService(
    private val tenantServiceClient: TenantServiceClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Generate GIN PDF from data
     *
     * @param ginData Aggregated GIN data
     * @return PDF as byte array
     */
    fun generateGinPdf(ginData: GinDataDto): ByteArray {
        logger.info("Generating GIN PDF for fulfillment: {}", ginData.fulfillmentRequestId)

        // 1. Fetch template from tenant service
        val template = fetchGinTemplate()
            ?: throw IllegalStateException("No GIN template found. Please configure a template first.")

        // 2. Render HTML using Thymeleaf
        val html = renderHtmlFromTemplate(template, ginData)

        // 3. Convert HTML to PDF
        val pdfBytes = convertHtmlToPdf(html)

        logger.info("GIN PDF generated successfully. Size: {} bytes", pdfBytes.size)
        return pdfBytes
    }

    /**
     * Fetch GIN template from tenant service
     */
    private fun fetchGinTemplate(): DocumentTemplateResponse? {
        return try {
            val response = tenantServiceClient.getActiveDocumentTemplate("GIN")
            val data = response.data
            if (response.success && data != null) {
                logger.debug("Fetched GIN template: {}", data.templateName)
                data
            } else {
                logger.warn("Failed to fetch GIN template from tenant service")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching GIN template", e)
            null
        }
    }

    /**
     * Render HTML from template using Thymeleaf
     */
    private fun renderHtmlFromTemplate(
        template: DocumentTemplateResponse,
        ginData: GinDataDto
    ): String {
        logger.debug("Rendering HTML from template: {}", template.templateName)

        // Create Thymeleaf template engine (using SpringTemplateEngine for SpEL support)
        val templateResolver = StringTemplateResolver()
        templateResolver.templateMode = TemplateMode.HTML
        templateResolver.isCacheable = false

        val templateEngine = SpringTemplateEngine()
        templateEngine.setTemplateResolver(templateResolver)

        // Create context and add variables
        val context = Context()
        context.setVariable("data", ginData)
        context.setVariable("commonConfig", template.commonConfig)
        context.setVariable("documentConfig", template.documentConfig)
        context.setVariable("cssContent", template.cssContent)

        // Process template
        val html = templateEngine.process(template.htmlTemplate, context)

        logger.debug("HTML rendered successfully. Length: {} characters", html.length)
        return html
    }

    /**
     * Convert HTML to PDF using Playwright (Headless Chrome)
     * Provides full modern CSS support including gradients, shadows, transforms, etc.
     */
    private fun convertHtmlToPdf(html: String): ByteArray {
        logger.debug("Converting HTML to PDF using Playwright")

        try {
            Playwright.create().use { playwright ->
                logger.debug("Launching headless Chromium browser")

                val browser: Browser = playwright.chromium().launch(
                    BrowserType.LaunchOptions()
                        .setHeadless(true)
                )

                val page: Page = browser.newPage()

                // Set viewport for consistent rendering
                page.setViewportSize(1440, 900)

                // Load HTML content
                logger.debug("Loading HTML content into browser")
                page.setContent(html)

                // Wait for any fonts or resources to load
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)

                // Configure PDF options
                val pdfOptions = Page.PdfOptions()
                    .setFormat("A4")
                    .setLandscape(true)
                    .setPrintBackground(true)

                // Generate PDF
                logger.debug("Generating PDF from rendered HTML")
                val pdfBytes = page.pdf(pdfOptions)

                // Cleanup
                browser.close()

                logger.debug("PDF conversion successful. Size: {} bytes", pdfBytes.size)
                return pdfBytes
            }
        } catch (e: Exception) {
            logger.error("Error converting HTML to PDF with Playwright", e)
            throw RuntimeException("Failed to generate PDF: ${e.message}", e)
        }
    }
}
