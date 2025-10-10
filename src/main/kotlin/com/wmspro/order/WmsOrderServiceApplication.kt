package com.wmspro.order

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WmsOrderServiceApplication

fun main(args: Array<String>) {
	runApplication<WmsOrderServiceApplication>(*args)
}
