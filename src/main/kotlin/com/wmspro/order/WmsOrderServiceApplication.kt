package com.wmspro.order

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.data.mongodb.config.EnableMongoAuditing

@SpringBootApplication(exclude = [
	MongoAutoConfiguration::class
])
@ComponentScan(
	basePackages = ["com.wmspro.order", "com.wmspro.common"],
	excludeFilters = [ComponentScan.Filter(
		type = FilterType.REGEX,
		pattern = ["com.wmspro.common.exception.*"]
	)]
)
@EnableFeignClients
@EnableMongoAuditing
class WmsOrderServiceApplication

fun main(args: Array<String>) {
	runApplication<WmsOrderServiceApplication>(*args)
}
