package com.wmspro.order.client.interceptor

import com.wmspro.common.tenant.TenantContext
import feign.RequestInterceptor
import feign.RequestTemplate
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class FeignClientInterceptor : RequestInterceptor {

    private val logger = LoggerFactory.getLogger(FeignClientInterceptor::class.java)

    override fun apply(requestTemplate: RequestTemplate) {
        try {
            val requestAttributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            val request = requestAttributes?.request

            // Forward Authorization header
            request?.let { httpRequest ->
                val authHeader = httpRequest.getHeader("Authorization")
                if (!authHeader.isNullOrBlank()) {
                    requestTemplate.header("Authorization", authHeader)
                    logger.debug("Forwarded Authorization header to Feign client")
                }
            }

            // Forward tenant context
            val tenantId = TenantContext.getCurrentTenant()
            if (!tenantId.isNullOrBlank()) {
                requestTemplate.header("X-Tenant-Id", tenantId)
                logger.debug("Forwarded tenant ID $tenantId to Feign client")
            }

        } catch (e: Exception) {
            logger.warn("Failed to forward request context to Feign client", e)
        }
    }
}
