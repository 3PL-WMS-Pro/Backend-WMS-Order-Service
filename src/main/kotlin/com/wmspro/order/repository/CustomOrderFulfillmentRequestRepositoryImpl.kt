package com.wmspro.order.repository

import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.enums.OfrStage
import com.wmspro.order.model.OrderFulfillmentRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class CustomOrderFulfillmentRequestRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : CustomOrderFulfillmentRequestRepository {

    override fun findOfrsByStageWithFilters(
        stage: OfrStage,
        accountId: Long?,
        searchTerm: String?,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
        pageable: Pageable
    ): Page<OrderFulfillmentRequest> {
        val query = Query()
        val criteria = mutableListOf<Criteria>()

        // Add stage-specific criteria
        criteria.add(getStageCriteria(stage))

        // Add accountId filter if present
        accountId?.let {
            criteria.add(Criteria.where("accountId").`is`(it))
        }

        // Add search filter (search across multiple fields)
        if (!searchTerm.isNullOrBlank()) {
            val searchRegex = ".*${searchTerm}.*"
            criteria.add(
                Criteria().orOperator(
                    Criteria.where("fulfillmentId").regex(searchRegex, "i"),
                    Criteria.where("customerInfo.name").regex(searchRegex, "i"),
                    Criteria.where("externalOrderId").regex(searchRegex, "i"),
                    Criteria.where("externalOrderNumber").regex(searchRegex, "i"),
                    Criteria.where("pickingTaskId").regex(searchRegex, "i"),
                    Criteria.where("packMoveTaskId").regex(searchRegex, "i"),
                    Criteria.where("pickPackMoveTaskId").regex(searchRegex, "i"),
                    Criteria.where("loadingTaskId").regex(searchRegex, "i"),
                    Criteria.where("shippingDetails.awbNumber").regex(searchRegex, "i"),
                    Criteria.where("ginNumber").regex(searchRegex, "i")
                )
            )
        }

        // Add date range filter
        val dateField = if (stage == OfrStage.PICKING_PENDING) "createdAt" else "updatedAt"

        if (dateFrom != null && dateTo != null) {
            criteria.add(Criteria.where(dateField).gte(dateFrom).lte(dateTo))
        } else if (dateFrom != null) {
            criteria.add(Criteria.where(dateField).gte(dateFrom))
        } else if (dateTo != null) {
            criteria.add(Criteria.where(dateField).lte(dateTo))
        }

        // Apply all criteria
        if (criteria.isNotEmpty()) {
            query.addCriteria(Criteria().andOperator(*criteria.toTypedArray()))
        }

        // Apply sorting from pageable
        query.with(pageable.sort)

        // Get total count before pagination
        val total = mongoTemplate.count(query, OrderFulfillmentRequest::class.java)

        // Apply pagination
        query.with(pageable)

        // Execute query
        val ofrs = mongoTemplate.find(query, OrderFulfillmentRequest::class.java)

        return PageImpl(ofrs, pageable, total)
    }

    override fun countOfrsByStage(
        stage: OfrStage,
        accountId: Long?
    ): Long {
        val query = Query()
        val criteria = mutableListOf<Criteria>()

        // Add stage-specific criteria
        criteria.add(getStageCriteria(stage))

        // Add accountId filter if present
        accountId?.let {
            criteria.add(Criteria.where("accountId").`is`(it))
        }

        // Apply all criteria
        if (criteria.isNotEmpty()) {
            query.addCriteria(Criteria().andOperator(*criteria.toTypedArray()))
        }

        return mongoTemplate.count(query, OrderFulfillmentRequest::class.java)
    }

    /**
     * Get stage-specific MongoDB criteria
     */
    private fun getStageCriteria(stage: OfrStage): Criteria {
        return when (stage) {
            OfrStage.PICKING_PENDING -> {
                // pickingTaskId exists AND != null AND statusHistory does NOT contain PICKED
                Criteria().andOperator(
                    Criteria.where("pickingTaskId").exists(true).ne(null),
                    Criteria.where("statusHistory").not().elemMatch(
                        Criteria.where("status").`is`(FulfillmentStatus.PICKED)
                    )
                )
            }

            OfrStage.PACK_MOVE_PENDING -> {
                // packMoveTaskId exists AND != null AND statusHistory does NOT contain PACKED
                Criteria().andOperator(
                    Criteria.where("packMoveTaskId").exists(true).ne(null),
                    Criteria.where("statusHistory").not().elemMatch(
                        Criteria.where("status").`is`(FulfillmentStatus.PACKED)
                    )
                )
            }

            OfrStage.PICK_PACK_MOVE_PENDING -> {
                // pickPackMoveTaskId exists AND != null AND statusHistory does NOT contain PACKED
                Criteria().andOperator(
                    Criteria.where("pickPackMoveTaskId").exists(true).ne(null),
                    Criteria.where("statusHistory").not().elemMatch(
                        Criteria.where("status").`is`(FulfillmentStatus.PACKED)
                    )
                )
            }

            OfrStage.READY_TO_DISPATCH -> {
                // statusHistory has an element where status = READY_TO_SHIP AND currentStatus = true
                Criteria.where("statusHistory").elemMatch(
                    Criteria.where("status").`is`(FulfillmentStatus.READY_TO_SHIP)
                        .and("currentStatus").`is`(true)
                )
            }

            OfrStage.LOADING_DONE_GIN_PENDING -> {
                // loadingTaskId exists AND != null AND ginNotification.sentToCustomer = false
                Criteria().andOperator(
                    Criteria.where("loadingTaskId").exists(true).ne(null),
                    Criteria.where("ginNotification.sentToCustomer").`is`(false)
                )
            }

            OfrStage.GIN_SENT -> {
                // ginNumber exists AND != null AND ginNotification.sentToCustomer = true
                Criteria().andOperator(
                    Criteria.where("ginNumber").exists(true).ne(null),
                    Criteria.where("ginNotification.sentToCustomer").`is`(true)
                )
            }
        }
    }
}
