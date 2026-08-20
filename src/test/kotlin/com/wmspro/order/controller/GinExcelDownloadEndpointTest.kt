package com.wmspro.order.controller

import com.wmspro.order.service.OfrGinService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The HTTP contract of the GIN Excel download — the mirror of `GrnExcelDownloadEndpointTest`.
 *
 * Both documents are served by the same shared writer, so the thing worth pinning separately is
 * the wiring around it: the content type a browser needs to open the file, and the status code the
 * customer is told when it cannot be produced.
 */
class GinExcelDownloadEndpointTest {

    private lateinit var ofrGinService: OfrGinService
    private lateinit var mockMvc: MockMvc

    private val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** A real ZIP magic number, so a byte-level assertion means something. */
    private val workbookBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)

    @BeforeEach
    fun setUp() {
        ofrGinService = Mockito.mock(OfrGinService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(OfrGinController(ofrGinService)).build()
    }

    private fun request(id: String = "OFR-001") =
        get("/api/v1/orders/gin/$id/download-excel")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")

    @Test
    @DisplayName("serves the workbook as a spreadsheet, named as an .xlsx attachment")
    fun servesWorkbookWithSpreadsheetContentType() {
        Mockito.`when`(ofrGinService.generateGinExcel("OFR-001", "Bearer test-token"))
            .thenReturn(workbookBytes)

        mockMvc.perform(request())
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", xlsx))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"GIN_OFR-001.xlsx\""))
            .andExpect(content().bytes(workbookBytes))
    }

    @Test
    @DisplayName("a missing fulfillment request is a 404")
    fun missingRequestIsNotFound() {
        Mockito.`when`(ofrGinService.generateGinExcel("OFR-NOPE", "Bearer test-token"))
            .thenThrow(IllegalArgumentException("Order Fulfillment Request not found"))

        mockMvc.perform(request("OFR-NOPE")).andExpect(status().isNotFound)
    }

    /**
     * The same defect as on the GRN side: POI raises `IllegalArgumentException` for its own limits,
     * and the controller reads that as "no such fulfillment request". A rendering fault must not be
     * reported to the customer as a document that does not exist.
     */
    @Test
    @DisplayName("a rendering failure is a 500, not a 404 claiming the request is missing")
    fun renderingFailureIsServerErrorNotNotFound() {
        Mockito.`when`(ofrGinService.generateGinExcel("OFR-001", "Bearer test-token"))
            .thenThrow(
                IllegalStateException(
                    "Failed to render GIN Excel for OFR-001",
                    IllegalArgumentException("The maximum length of cell contents (text) is 32767 characters")
                )
            )

        mockMvc.perform(request()).andExpect(status().isInternalServerError)
    }

    /**
     * Aggregation calls other services on the customer's behalf. A dropped token turns every
     * download into an upstream 401 that surfaces as a missing document.
     */
    @Test
    @DisplayName("the authorization header is passed through to the service")
    fun forwardsAuthToken() {
        Mockito.`when`(ofrGinService.generateGinExcel("OFR-001", "Bearer test-token"))
            .thenReturn(workbookBytes)

        mockMvc.perform(request()).andExpect(status().isOk)

        Mockito.verify(ofrGinService).generateGinExcel("OFR-001", "Bearer test-token")
    }

    /**
     * The endpoint reads the header with `?: ""` rather than requiring it, so a request without one
     * reaches the service with an empty token instead of being rejected at the door. Pinned as the
     * behaviour it is, so that if it ever becomes a 401 that is a deliberate change and not a
     * surprise.
     */
    @Test
    @DisplayName("a request with no authorization header still reaches the service, with an empty token")
    fun missingAuthHeaderPassesEmptyToken() {
        Mockito.`when`(ofrGinService.generateGinExcel("OFR-001", "")).thenReturn(workbookBytes)

        mockMvc.perform(get("/api/v1/orders/gin/OFR-001/download-excel"))
            .andExpect(status().isOk)

        Mockito.verify(ofrGinService).generateGinExcel("OFR-001", "")
    }
}
