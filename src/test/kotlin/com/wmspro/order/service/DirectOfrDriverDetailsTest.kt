package com.wmspro.order.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.jwt.JwtTokenExtractor
import com.wmspro.common.service.AccountService
import com.wmspro.order.client.*
import com.wmspro.order.config.FeignConfig
import com.wmspro.order.dto.*
import com.wmspro.order.enums.AwbCondition
import com.wmspro.order.enums.ItemType
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime

/**
 * The phone's Dispatch screen records who drove the goods away. Direct orders have no loading task,
 * so the driver used to be dropped: the request had no field for it and the order had nowhere to
 * keep it. The name and phone are optional, travel next to the truck number, and are stored on the
 * order's loading documents - which now exist on every direct order, photos or not.
 */
class DirectOfrDriverDetailsTest {

    /** The Order Service's own mapper: case-sensitive enums, unknown properties ignored. */
    private val objectMapper = FeignConfig().objectMapper()

    private lateinit var ofrRepository: OrderFulfillmentRequestRepository
    private lateinit var inventoryClient: InventoryServiceClient
    private lateinit var service: OrderFulfillmentService
    private val authToken = "Bearer test-token"
    private var sequence = 100

    @BeforeEach
    fun setUp() {
        ofrRepository = mock(OrderFulfillmentRequestRepository::class.java)
        inventoryClient = mock(InventoryServiceClient::class.java)
        val sequences = mock(SequenceGeneratorService::class.java)
        `when`(sequences.generateOfrId()).thenReturn("OFR-00533")
        `when`(sequences.generateGinNumber()).thenReturn("GIN-2026-000532")
        `when`(sequences.generateLineItemId()).thenAnswer { "LI-${sequence++}" }
        `when`(sequences.generatePackageId()).thenAnswer { "PKG-${sequence++}" }
        `when`(ofrRepository.existsByPackagesPackageBarcode(anyString())).thenReturn(false)
        `when`(ofrRepository.save(any(OrderFulfillmentRequest::class.java))).thenAnswer { it.arguments[0] }
        `when`(inventoryClient.changeStorageItemLocation(anyString(), any(ChangeLocationRequest::class.java), anyString()))
            .thenAnswer { invocation -> shipped(invocation.arguments[0] as String) }
        `when`(inventoryClient.consumePackageBarcodesFromRequest(any(ConsumePackageBarcodesFromRequestRequest::class.java), anyString()))
            .thenReturn(ApiResponse.success(BarcodeConsumptionResponse(1, 1, 0, emptyList())))

        service = OrderFulfillmentService(
            ofrRepository, sequences, mock(ProductServiceClient::class.java), inventoryClient,
            mock(TaskServiceClient::class.java), mock(JwtTokenExtractor::class.java), mock(AccountService::class.java)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the phone's body parses with the driver name and phone next to the truck`() {
        val request = objectMapper.readValue<CreateDirectOfrRequest>(phoneBody(driver = true))

        assertEquals("DXB A 12345", request.truckNumber)
        assertEquals("Rahul Kumar", request.driverName)
        assertEquals("+971 501234567", request.driverPhone)
        // The rest of the body is untouched by the new fields.
        assertEquals(AwbCondition.NO_AWB, request.awbCondition)
        assertEquals("ITM-501", request.packages.single().items.single().itemBarcode)
        assertEquals(emptyList<String>(), request.loadingDocuments!!.packagePhotosUrls)
    }

    @Test
    fun `a body without driver details - the web's today - parses with both left null`() {
        val request = objectMapper.readValue<CreateDirectOfrRequest>(phoneBody(driver = false))

        assertNull(request.driverName)
        assertNull(request.driverPhone)
        assertEquals("DXB A 12345", request.truckNumber)
    }

    // ---------------------------------------------------------------------------------------------
    // Storing and echoing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the truck and driver are stored on the order even when no documents were uploaded`() {
        val response = service.createDirectOfrAndProcess(
            request(driverName = "  Rahul Kumar ", driverPhone = "+971 501234567", loadingDocuments = null),
            "ops@infinity", authToken
        )

        val documents = saved().loadingDocuments
        assertNotNull(documents, "a direct order always records its truck")
        assertEquals("DXB A 12345", documents!!.truckNumber)
        assertEquals("Rahul Kumar", documents.driverName, "the name is trimmed")
        assertEquals("+971 501234567", documents.driverPhone)
        assertEquals(emptyList<String>(), documents.packagePhotosUrls)
        assertNull(documents.truckDriverPhotoUrl)
        assertNull(documents.truckDriverIdProofUrl)

        assertEquals("Rahul Kumar", response.driverName)
        assertEquals("+971 501234567", response.driverPhone)
        assertEquals("DXB A 12345", response.truckNumber)
    }

    @Test
    fun `a blank driver phone is stored as null, not as an empty string`() {
        val response = service.createDirectOfrAndProcess(
            request(driverName = "Rahul Kumar", driverPhone = "   ", loadingDocuments = null),
            "ops@infinity", authToken
        )

        assertNull(saved().loadingDocuments!!.driverPhone)
        assertNull(response.driverPhone)
        assertEquals("Rahul Kumar", response.driverName)
    }

    @Test
    fun `uploaded documents are kept beside the truck and driver, and the GIN attachments are unchanged`() {
        val documents = LoadingDocumentsDto(
            packagePhotosUrls = listOf("https://api.wms.freighai.com/api/v1/files/F-1/download"),
            truckDriverPhotoUrl = "https://api.wms.freighai.com/api/v1/files/F-2/download",
            truckDriverIdProofUrl = "https://api.wms.freighai.com/api/v1/files/F-3/download"
        )
        service.createDirectOfrAndProcess(
            request(driverName = "Rahul Kumar", driverPhone = null, loadingDocuments = documents),
            "ops@infinity", authToken
        )

        val order = saved()
        val stored = order.loadingDocuments!!
        assertEquals(documents.packagePhotosUrls, stored.packagePhotosUrls)
        assertEquals(documents.truckDriverPhotoUrl, stored.truckDriverPhotoUrl)
        assertEquals(documents.truckDriverIdProofUrl, stored.truckDriverIdProofUrl)
        assertEquals("DXB A 12345", stored.truckNumber)
        assertEquals("Rahul Kumar", stored.driverName)
        assertNull(stored.driverPhone)
        assertEquals(
            listOf("Package_Photo_1.jpg", "Driver_Photo.jpg", "Driver_ID_Proof.pdf"),
            order.ginNotification!!.attachments.map { it.fileName }
        )
    }

    /**
     * Building the loading documents on every direct order must not make an order without uploads
     * look as if it had some: the GIN attachments come from the request, not from the model.
     */
    @Test
    fun `an order without uploaded documents still has no GIN attachments`() {
        service.createDirectOfrAndProcess(
            request(driverName = "Rahul Kumar", driverPhone = "+971 501234567", loadingDocuments = null),
            "ops@infinity", authToken
        )

        assertEquals(emptyList<Any>(), saved().ginNotification!!.attachments)
    }

    /**
     * The web sends no driver today. Its response is the one it always had, plus two null fields -
     * nothing renamed, nothing removed.
     */
    @Test
    fun `a request without driver details gets the same response plus two null fields`() {
        val response = service.createDirectOfrAndProcess(
            request(driverName = null, driverPhone = null, loadingDocuments = null), "ops@infinity", authToken
        )

        assertNull(response.driverName)
        assertNull(response.driverPhone)
        assertNull(saved().loadingDocuments!!.driverName)

        val keys = objectMapper.readTree(objectMapper.writeValueAsString(response)).fieldNames().asSequence().toSet()
        assertEquals(
            setOf(
                "fulfillmentId", "ginNumber", "fulfillmentStatus", "accountId", "totalPackages", "totalItems",
                "truckNumber", "awbGenerated", "awbDetails", "createdAt", "updatedAt", "driverName", "driverPhone"
            ),
            keys
        )
        assertEquals("OFR-00533", response.fulfillmentId)
        assertEquals("GIN-2026-000532", response.ginNumber)
        assertEquals(1, response.totalPackages)
        assertEquals(1, response.totalItems)
        assertTrue(!response.awbGenerated)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** What `buildDispatchPayload` on the phone sends, with `ginDate` from `Date.toISOString()`. */
    private fun phoneBody(driver: Boolean) = """
        {
          "accountId": 1001675,
          "customerInfo": { "name": "AL BOOM MARINE", "email": "ops@alboom.ae", "phone": "+971 41234567" },
          "clientReferenceNum": null,
          "shippingAddress": {
            "name": "Receiving Bay", "company": "", "addressLine1": "Yard 3", "addressLine2": "",
            "city": "Dubai", "state": "", "country": "AE", "postalCode": "", "phone": null
          },
          "ginDate": "2026-10-23T08:00:00.000Z",
          "orderValue": { "currency": "AED", "subtotal": 0, "shipping": 0, "tax": 0, "total": 0 },
          "shippingDetails": { "carrier": null, "requestedServiceType": null },
          "packages": [{
            "packageBarcode": "PKG-00101",
            "dimensions": { "length": 40, "width": 30, "height": 20, "unit": "cm" },
            "weight": { "value": 0, "unit": "kg" },
            "items": [{ "itemType": "SKU_ITEM", "storageItemId": 501, "itemBarcode": "ITM-501", "skuId": 101 }]
          }],
          "awbCondition": "NO_AWB",
          "truckNumber": "DXB A 12345",
          ${if (driver) "\"driverName\": \"Rahul Kumar\", \"driverPhone\": \"+971 501234567\"," else ""}
          "loadingDocuments": { "packagePhotosUrls": [], "truckDriverPhotoUrl": null, "truckDriverIdProofUrl": null },
          "fulfillmentSource": "WEB_PORTAL",
          "priority": "STANDARD",
          "notes": "NOT_AVAILABLE",
          "externalOrderId": "NOT_AVAILABLE",
          "externalOrderNumber": "NOT_AVAILABLE"
        }
    """.trimIndent()

    private fun request(driverName: String?, driverPhone: String?, loadingDocuments: LoadingDocumentsDto?) =
        CreateDirectOfrRequest(
            accountId = 1001675L,
            customerInfo = CustomerInfoDto(name = "AL BOOM MARINE", email = "ops@alboom.ae"),
            shippingAddress = ShippingAddressDto(addressLine1 = "Yard 3", city = "Dubai", country = "AE"),
            ginDate = LocalDateTime.of(2026, 10, 23, 12, 0),
            packages = listOf(
                DirectPackageDto(
                    packageBarcode = "PKG-00101",
                    dimensions = PackageDimensionsDto(40.0, 30.0, 20.0, "cm"),
                    weight = PackageWeightDto(0.0, "kg"),
                    items = listOf(
                        DirectPackageItemDto(itemType = ItemType.SKU_ITEM, storageItemId = 501L, itemBarcode = "ITM-501", skuId = 101L)
                    )
                )
            ),
            awbCondition = AwbCondition.NO_AWB,
            truckNumber = "DXB A 12345",
            driverName = driverName,
            driverPhone = driverPhone,
            loadingDocuments = loadingDocuments
        )

    private fun shipped(barcode: String): ApiResponse<ChangeLocationResponse> = ApiResponse.success(
        ChangeLocationResponse(
            storageItemId = 501L, itemCode = barcode, itemType = "SKU_ITEM",
            previousLocation = LocationChangeInfo("WH1-A-1-1-A", "WAREHOUSE"),
            newLocation = LocationChangeInfo("IN_TRANSIT", "IN_TRANSIT"),
            locationUpdatedAt = "2026-10-23T12:00:00",
            transactionCreated = TransactionInfo("TXN-1", "LOCATION_CHANGE", "ops@infinity", "OFR-00533"),
            message = "moved"
        )
    )

    private fun saved(): OrderFulfillmentRequest {
        val captor = ArgumentCaptor.forClass(OrderFulfillmentRequest::class.java)
        verify(ofrRepository).save(captor.capture())
        return captor.value
    }

    // Mockito's matchers return null, which Kotlin rejects for non-null parameters.
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(type: Class<T>): T {
        ArgumentMatchers.any(type)
        return null as T
    }
}
