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
}
