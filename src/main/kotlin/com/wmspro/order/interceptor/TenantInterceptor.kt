package com.wmspro.order.interceptor

import com.wmspro.common.tenant.TenantContext
import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.utils.TenantConnectionFetcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class TenantInterceptor(
    private val tenantConnectionFetcher: TenantConnectionFetcher
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(TenantInterceptor::class.java)

    companion object {
        private val CENTRAL_DB_PATHS = listOf(
            "/actuator",
            "/health",
            "/swagger-ui",
            "/v3/api-docs",
            "/error"
        )
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val requestPath = request.requestURI

        // Skip tenant context for central database paths
        if (CENTRAL_DB_PATHS.any { requestPath.startsWith(it) }) {
            logger.debug("Skipping tenant context for central DB path: $requestPath")
            return true
        }

        val tenantId = extractTenantId(request)

        if (tenantId == null) {
            logger.error("Tenant context required but not found")
            response.sendError(401, "Tenant context required")
            return false
        }

        logger.debug("Extracted tenant ID: $tenantId")

        try {
            val dbConnection = tenantConnectionFetcher.fetchTenantConnection(tenantId.toInt())
            if (dbConnection == null) {
                logger.error("Tenant not found or inactive: $tenantId")
                response.sendError(401, "Tenant not found or inactive: $tenantId")
                return false
            }

            logger.debug("Setting tenant context: $tenantId")
            TenantContext.setCurrentTenant(tenantId)
            MongoConnectionStorage.setConnection(dbConnection)

            return true

        } catch (e: Exception) {
            logger.error("Error setting up tenant context for tenant $tenantId", e)
            response.sendError(500, "Error setting up tenant context")
            return false
        }
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        logger.debug("Clearing tenant context")
        MongoConnectionStorage.clear()
        TenantContext.clear()
    }

    private fun extractTenantId(request: HttpServletRequest): String? {
        // Check headers first
        var tenantId = request.getHeader("X-Client-ID")
            ?: request.getHeader("X-Tenant-ID")
            ?: request.getHeader("X-Client-Id")
            ?: request.getHeader("X-Tenant-Id")

        // Check query parameters if not in headers
        if (tenantId == null) {
            tenantId = request.getParameter("tenantId")
                ?: request.getParameter("clientId")
                ?: request.getParameter("tenant_id")
                ?: request.getParameter("client_id")
        }

        return tenantId?.trim()
    }
}
