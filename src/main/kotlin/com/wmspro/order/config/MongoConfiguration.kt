package com.wmspro.order.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.core.MongoTemplate

@Configuration
class MongoConfiguration {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${spring.data.mongodb.uri}")
    private lateinit var defaultConnectionUri: String

    /**
     * Tenant-aware MongoDatabaseFactory bean for Spring Data Repositories
     * This is a singleton bean, but it internally reads the tenant's connection from ThreadLocal
     * for each database operation, ensuring proper multi-tenancy isolation.
     */
    @Bean
    fun mongoDatabaseFactory(): MongoDatabaseFactory {
        logger.info("Creating TenantAwareMongoDatabaseFactory - singleton with ThreadLocal connection resolution")
        return TenantAwareMongoDatabaseFactory(
            defaultConnectionUri,
            "wms_order"
        )
    }

    /**
     * MongoTemplate bean for manual MongoDB operations
     * Uses the tenant-aware factory which handles connection routing per request
     */
    @Bean
    fun mongoTemplate(mongoDatabaseFactory: MongoDatabaseFactory): MongoTemplate {
        logger.info("Creating MongoTemplate with TenantAwareMongoDatabaseFactory")
        return MongoTemplate(mongoDatabaseFactory)
    }

    /*
     * Deliberately no MongoTransactionManager bean.
     *
     * One was added alongside the claim-safe billing work and had to be removed: the deployment is
     * a standalone mongod, and multi-document transactions require a replica set or mongos. The
     * bean does not fail at startup — it silently activates every @Transactional annotation already
     * in this service, including the class-level one on OrderFulfillmentService. Creating an order
     * request then failed with "This MongoDB deployment does not support retryable writes", on a
     * path that had worked for months.
     *
     * The billing claim path never needed it. OrderFulfillmentBillingClaimService does its work
     * with a single findAndModify, and MongoDB makes single-document updates atomic on their own —
     * which is what makes the claim safe, not a surrounding transaction.
     *
     * Before adding one back: check the topology first (db.hello().setName must be non-null), and
     * note that retryWrites=false does not help — it disables a different mechanism.
     */
}
