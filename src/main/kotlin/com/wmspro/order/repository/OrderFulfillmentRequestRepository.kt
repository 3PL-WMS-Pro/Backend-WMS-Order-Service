package com.wmspro.order.repository

import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.model.OrderFulfillmentRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface OrderFulfillmentRequestRepository : MongoRepository<OrderFulfillmentRequest, String> {

    fun findByFulfillmentId(fulfillmentId: String): Optional<OrderFulfillmentRequest>

    fun findByAccountId(accountId: Long, pageable: Pageable): Page<OrderFulfillmentRequest>

    fun findByAccountIdAndFulfillmentStatus(
        accountId: Long,
        fulfillmentStatus: FulfillmentStatus,
        pageable: Pageable
    ): Page<OrderFulfillmentRequest>

    fun findByFulfillmentStatus(
        fulfillmentStatus: FulfillmentStatus,
        pageable: Pageable
    ): Page<OrderFulfillmentRequest>

    fun findAllBy(pageable: Pageable): Page<OrderFulfillmentRequest>

    @Query("{ 'externalOrderId': ?0 }")
    fun findByExternalOrderId(externalOrderId: String): Optional<OrderFulfillmentRequest>

    fun existsByFulfillmentId(fulfillmentId: String): Boolean
}
