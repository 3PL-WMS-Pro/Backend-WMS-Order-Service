package com.wmspro.order.config

import com.wmspro.common.mongo.MongoConnectionStorage
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.data.mongodb.core.MongoTemplate

@Configuration
class MongoConfiguration {

    @Value("\${spring.data.mongodb.uri}")
    private lateinit var defaultConnectionUri: String

    @Bean
    @Lazy
    fun mongoTemplate(): MongoTemplate {
        val connectionUri = try {
            MongoConnectionStorage.getConnection()
        } catch (e: Exception) {
            defaultConnectionUri
        }

        return MongoTemplate(
            DatabaseConfiguration(
                connectionUri,
                "wms_order"
            )
        )
    }
}
