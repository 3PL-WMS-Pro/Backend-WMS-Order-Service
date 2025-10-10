package com.wmspro.order.client

import com.wmspro.common.dto.ApiResponse
import com.wmspro.order.dto.CreateTaskRequest
import com.wmspro.order.dto.TaskResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader

@FeignClient(name = "\${wms.services.task-service.name}", path = "/api/v1/tasks")
interface TaskServiceClient {

    @PostMapping
    fun createTask(
        @RequestBody request: CreateTaskRequest,
        @RequestHeader("Authorization") authToken: String
    ): ApiResponse<TaskResponse>
}
