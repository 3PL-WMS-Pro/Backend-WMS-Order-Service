package com.wmspro.order.exception

class OrderFulfillmentRequestNotFoundException(message: String) : RuntimeException(message)

class InsufficientInventoryException(message: String) : RuntimeException(message)

class InvalidOrderRequestException(message: String) : RuntimeException(message)

class TaskCreationFailedException(message: String) : RuntimeException(message)

class DuplicateOrderException(message: String) : RuntimeException(message)
