package com.wmspro.order.service

import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.stereotype.Service
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Document to store sequence counters for different collections
 */
@Document(collection = "sequences")
data class SequenceCounter(
    @Id
    val id: String,
    val sequenceValue: Long
)

/**
 * Service to generate unique IDs for different collections in Order Service
 * Uses MongoDB's atomic findAndModify operation to ensure thread-safety
 */
@Service
class SequenceGeneratorService(
    private val mongoTemplate: MongoTemplate
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val OFR_SEQUENCE_NAME = "ofr_sequence"
        const val LINE_ITEM_SEQUENCE_NAME = "line_item_sequence"
        const val PACKAGE_SEQUENCE_NAME = "package_sequence"
        const val GIN_SEQUENCE_NAME = "gin_sequence"
    }

    /**
     * Generate next sequence ID for OrderFulfillmentRequest
     * Format: OFR-{sequential}
     * Example: OFR-00001, OFR-00002
     */
    fun generateOfrId(): String {
        val sequenceValue = generateSequence(OFR_SEQUENCE_NAME)
        return String.format("OFR-%05d", sequenceValue)
    }

    /**
     * Generate next line item ID
     * Format: LI-{sequential}
     * Example: LI-001, LI-002
     */
    fun generateLineItemId(): String {
        val sequenceValue = generateSequence(LINE_ITEM_SEQUENCE_NAME)
        return String.format("LI-%03d", sequenceValue)
    }

    /**
     * Generate next package ID
     * Format: PKG-{sequential}
     * Example: PKG-001, PKG-002
     */
    fun generatePackageId(): String {
        val sequenceValue = generateSequence(PACKAGE_SEQUENCE_NAME)
        return String.format("PKG-%03d", sequenceValue)
    }

    /**
     * Generate next GIN (Goods Issue Note) ID
     * Format: GIN-{year}-{sequential}
     * Example: GIN-2025-000001, GIN-2025-000002
     */
    fun generateGinNumber(): String {
        val currentYear = java.time.Year.now().value
        val sequenceValue = generateSequence(GIN_SEQUENCE_NAME)
        return String.format("GIN-%d-%06d", currentYear, sequenceValue)
    }

    /**
     * Generic method to generate sequence for any collection
     */
    private fun generateSequence(sequenceName: String): Long {
        logger.debug("Generating next sequence for: {}", sequenceName)

        // Find and atomically increment the sequence counter
        val query = Query(Criteria.where("_id").`is`(sequenceName))
        val update = Update().inc("sequenceValue", 1)
        val options = FindAndModifyOptions()
            .returnNew(true) // Return the updated document
            .upsert(true) // Create if doesn't exist

        val counter = mongoTemplate.findAndModify(
            query,
            update,
            options,
            SequenceCounter::class.java
        )

        val generatedId = counter?.sequenceValue ?: 1L
        logger.debug("Generated ID {} for sequence: {}", generatedId, sequenceName)

        return generatedId
    }

    /**
     * Initialize or reset a sequence counter
     * Should be called during application startup if needed
     */
    fun initializeSequence(sequenceName: String, startValue: Long = 0L) {
        logger.info("Initializing sequence {} with start value: {}", sequenceName, startValue)

        val query = Query(Criteria.where("_id").`is`(sequenceName))
        val update = Update().set("sequenceValue", startValue)

        mongoTemplate.upsert(query, update, SequenceCounter::class.java)
    }

    /**
     * Get current sequence value without incrementing
     */
    fun getCurrentSequenceValue(sequenceName: String): Long {
        val query = Query(Criteria.where("_id").`is`(sequenceName))
        val counter = mongoTemplate.findOne(query, SequenceCounter::class.java)
        return counter?.sequenceValue ?: 0L
    }
}
