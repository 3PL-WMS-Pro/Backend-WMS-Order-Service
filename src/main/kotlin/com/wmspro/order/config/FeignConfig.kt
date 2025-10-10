package com.wmspro.order.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.wmspro.order.client.interceptor.FeignClientInterceptor
import feign.RequestInterceptor
import feign.codec.Decoder
import feign.codec.Encoder
import org.springframework.beans.factory.ObjectFactory
import org.springframework.boot.autoconfigure.http.HttpMessageConverters
import org.springframework.cloud.openfeign.support.SpringDecoder
import org.springframework.cloud.openfeign.support.SpringEncoder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter

@Configuration
class FeignConfig {

    @Bean
    fun objectMapper(): ObjectMapper {
        return ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            findAndRegisterModules()
        }
    }

    @Bean
    fun feignDecoder(objectMapper: ObjectMapper): Decoder {
        val converter = MappingJackson2HttpMessageConverter(objectMapper)
        val objectFactory = ObjectFactory { HttpMessageConverters(converter) }
        return SpringDecoder(objectFactory)
    }

    @Bean
    fun feignEncoder(objectMapper: ObjectMapper): Encoder {
        val converter = MappingJackson2HttpMessageConverter(objectMapper)
        val objectFactory = ObjectFactory { HttpMessageConverters(converter) }
        return SpringEncoder(objectFactory)
    }

    @Bean
    fun feignClientInterceptor(): RequestInterceptor {
        return FeignClientInterceptor()
    }
}
