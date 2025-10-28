package com.wmspro.order.config

import com.mongodb.ClientSessionOptions
import com.mongodb.client.MongoDatabase
import com.wmspro.common.mongo.MongoConnectionStorage
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.support.PersistenceExceptionTranslator
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.core.MongoExceptionTranslator
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory

/**
 * Tenant-aware MongoDB Database Factory
 * This factory is a singleton bean but internally reads the tenant's connection from ThreadLocal
 * for each database access, ensuring proper multi-tenancy isolation.
 */
class TenantAwareMongoDatabaseFactory(
    private val defaultConnectionUri: String,
    private val defaultDatabase: String
) : MongoDatabaseFactory {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val exceptionTranslator = MongoExceptionTranslator()

    /**
     * Returns the MongoDatabase for the current tenant context.
     * Reads the tenant-specific connection from ThreadLocal storage.
     */
    override fun getMongoDatabase(): MongoDatabase {
        val connectionUri = try {
            val uri = MongoConnectionStorage.getConnection()
            logger.debug("Getting database for tenant-specific connection")
            uri
        } catch (e: Exception) {
            logger.warn("No tenant connection in ThreadLocal, using default connection. This should only happen during startup or health checks.")
            defaultConnectionUri
        }

        // Create a factory for the specific connection and get the database
        val factory = DatabaseConfiguration(connectionUri, defaultDatabase)
        return factory.mongoDatabase
    }

    /**
     * Returns the MongoDatabase with a specific database name override.
     */
    override fun getMongoDatabase(dbName: String): MongoDatabase {
        val connectionUri = try {
            MongoConnectionStorage.getConnection()
        } catch (e: Exception) {
            logger.warn("No tenant connection in ThreadLocal for database: $dbName")
            defaultConnectionUri
        }

        val factory = DatabaseConfiguration(connectionUri, dbName)
        return factory.mongoDatabase
    }

    /**
     * Session support - delegates to a factory instance
     */
    override fun getSession(options: ClientSessionOptions): com.mongodb.client.ClientSession {
        val connectionUri = try {
            MongoConnectionStorage.getConnection()
        } catch (e: Exception) {
            defaultConnectionUri
        }

        val factory = DatabaseConfiguration(connectionUri, defaultDatabase)
        return factory.getSession(options)
    }

    /**
     * Starts a session with the current tenant's connection
     */
    override fun withSession(session: com.mongodb.client.ClientSession): MongoDatabaseFactory {
        val connectionUri = try {
            MongoConnectionStorage.getConnection()
        } catch (e: Exception) {
            defaultConnectionUri
        }

        val factory = DatabaseConfiguration(connectionUri, defaultDatabase)
        return factory.withSession(session)
    }

    /**
     * Returns whether this factory is associated with a session
     */
    override fun isTransactionActive(): Boolean {
        return false
    }

    /**
     * Returns the exception translator for MongoDB exceptions
     */
    override fun getExceptionTranslator(): PersistenceExceptionTranslator {
        return exceptionTranslator
    }
}
