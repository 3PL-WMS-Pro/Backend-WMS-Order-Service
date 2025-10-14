package com.wmspro.order.repository

import com.wmspro.order.enums.OfrStage
import com.wmspro.order.model.OrderFulfillmentRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime

interface CustomOrderFulfillmentRequestRepository {

    /**
     * Find OFRs by stage with dynamic filtering
     * All filters are applied at database level for optimal performance
     */
    fun findOfrsByStageWithFilters(
        stage: OfrStage,
        accountId: Long?,
        searchTerm: String?,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
        pageable: Pageable
    ): Page<OrderFulfillmentRequest>

    /**
     * Count OFRs by stage with optional account filter
     */
    fun countOfrsByStage(
        stage: OfrStage,
        accountId: Long?
    ): Long
}
