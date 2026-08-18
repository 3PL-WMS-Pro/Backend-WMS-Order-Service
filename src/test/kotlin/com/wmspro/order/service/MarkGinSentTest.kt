package com.wmspro.order.service

import com.wmspro.common.jwt.JwtTokenExtractor
import com.wmspro.common.service.AccountService
import com.wmspro.order.dto.MarkGinSentRequest
import com.wmspro.order.enums.ExecutionApproach
import com.wmspro.order.enums.FulfillmentSource
import com.wmspro.order.enums.FulfillmentStatus
import com.wmspro.order.model.CustomerInfo
import com.wmspro.order.model.GinAttachment
import com.wmspro.order.model.GinNotification
import com.wmspro.order.model.OrderFulfillmentRequest
import com.wmspro.order.model.ShippingAddress
import com.wmspro.order.model.ShippingDetails
import com.wmspro.order.model.StatusHistory
import com.wmspro.order.repository.OrderFulfillmentRequestRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/**
 * Covers `OfrGinService.markGinSent` — recording a GIN as sent without emailing it.
 *
 * Plain unit tests with mocked collaborators: no Spring context, so these do not need Mongo,
 * Eureka or a mail server to run.
 */
class MarkGinSentTest {

    private lateinit var ofrRepository: OrderFulfillmentRequestRepository
    private lateinit var jwtTokenExtractor: JwtTokenExtractor
    private lateinit var ginEmailService: GinEmailService
    private lateinit var service: OfrGinService

    private val authToken = "Bearer test-token"
    private val username = "ops@infinitylogisticsme.com"

    @BeforeEach
    fun setUp() {
        ofrRepository = mock(OrderFulfillmentRequestRepository::class.java)
        jwtTokenExtractor = mock(JwtTokenExtractor::class.java)
        ginEmailService = mock(GinEmailService::class.java)

        service = OfrGinService(
            ofrRepository = ofrRepository,
            accountService = mock(AccountService::class.java),
            ginDataAggregationService = mock(GinDataAggregationService::class.java),
            ginPdfGenerationService = mock(GinPdfGenerationService::class.java),
            ginEmailService = ginEmailService,
            jwtTokenExtractor = jwtTokenExtractor
        )

        `when`(jwtTokenExtractor.extractUsername(authToken)).thenReturn(username)
    }

    /**
     * An order that has reached the point where a GIN legitimately exists. `READY_TO_SHIP` is in
     * the history by default because `markGinSent` refuses anything earlier — a goods-ISSUE note
     * for goods still being picked would be a lie.
     */
    private fun ofr(
        fulfillmentId: String = "OFR-001",
        ginNumber: String? = "GIN-2026-00042",
        ginNotification: GinNotification? = null
    ) = OrderFulfillmentRequest(
        fulfillmentId = fulfillmentId,
        accountId = 6994L,
        fulfillmentSource = FulfillmentSource.MANUAL,
        executionApproach = ExecutionApproach.PICK_PACK_MOVE_TOGETHER,
        customerInfo = CustomerInfo(name = "Digital Stout", email = "ops@digitalstout.ae"),
        shippingAddress = ShippingAddress(addressLine1 = "Warehouse 4", city = "Sharjah", country = "AE"),
        shippingDetails = ShippingDetails(),
        statusHistory = mutableListOf(
            StatusHistory(status = FulfillmentStatus.READY_TO_SHIP, currentStatus = true)
        ),
        ginNumber = ginNumber,
        ginNotification = ginNotification
    )

    private fun captureSaved(): OrderFulfillmentRequest {
        val captor = ArgumentCaptor.forClass(OrderFulfillmentRequest::class.java)
        verify(ofrRepository).save(captor.capture())
        return captor.value
    }

    @Test
    @DisplayName("records the GIN as sent, flagged as manual, without sending an email")
    fun marksAsSent() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr()))

        service.markGinSent("OFR-001", MarkGinSentRequest(note = "handed over at the gate"), authToken)

        val saved = captureSaved()
        val notification = saved.ginNotification!!

        assertTrue(notification.sentToCustomer, "sentToCustomer drives the derived GIN_SENT stage")
        assertTrue(notification.markedManually, "must be distinguishable from a genuinely emailed GIN")
        assertNotNull(notification.sentAt)
        assertEquals("handed over at the gate", notification.note)
        assertEquals(username, saved.updatedBy)

        // The whole point of the feature: nothing is emailed.
        verifyNoInteractions(ginEmailService)
    }

    @Test
    @DisplayName("invents no recipient when none was ever recorded")
    fun inventsNoRecipient() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr()))

        service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)

        val notification = captureSaved().ginNotification!!
        assertNull(notification.toEmail)
        assertNull(notification.subject)
        assertNull(notification.emailContent)
        assertTrue(notification.ccEmails.isEmpty())
    }

    /**
     * The opposite case, and the one the previous version of this test silently failed to cover:
     * when an earlier send DID record email details, marking carries them forward rather than
     * destroying them. The UI is what suppresses them — `OverviewTab` hides the Recipient row when
     * `markedManually` — because losing recorded data is worse than not displaying it.
     */
    @Test
    @DisplayName("carries forward email details recorded by an earlier attempt, rather than erasing them")
    fun preservesPreviouslyRecordedEmailDetails() {
        val earlierAttempt = GinNotification(
            sentToCustomer = false,
            toEmail = "ops@digitalstout.ae",
            ccEmails = listOf("finance@digitalstout.ae"),
            subject = "GIN GIN-2026-00042",
            emailContent = "Please find attached."
        )
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr(ginNotification = earlierAttempt)))

        service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)

        val notification = captureSaved().ginNotification!!
        assertEquals("ops@digitalstout.ae", notification.toEmail)
        assertEquals(listOf("finance@digitalstout.ae"), notification.ccEmails)
        assertEquals("GIN GIN-2026-00042", notification.subject)
        assertTrue(notification.markedManually)
    }

    @Test
    @DisplayName("records who marked it, in a field later OFR writes cannot overwrite")
    fun recordsMarkedBy() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr()))

        service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)

        assertEquals(username, captureSaved().ginNotification!!.markedBy)
    }

    @Test
    @DisplayName("refuses an order that has not reached READY_TO_SHIP")
    fun refusesBeforeReadyToShip() {
        val notReady = ofr().copy(statusHistory = mutableListOf(), loadingTaskId = null)
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(notReady))

        val error = assertThrows(IllegalStateException::class.java) {
            service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)
        }

        assertTrue(error.message!!.contains("READY_TO_SHIP"))
        verify(ofrRepository).findById("OFR-001")
        verifyNoMoreInteractions(ofrRepository)
    }

    @Test
    @DisplayName("refuses a future GIN date, which could never be corrected afterwards")
    fun refusesFutureDate() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr()))

        val error = assertThrows(IllegalStateException::class.java) {
            service.markGinSent("OFR-001", MarkGinSentRequest(ginDate = LocalDate.now().plusDays(1)), authToken)
        }

        assertTrue(error.message!!.contains("in the future"))
        verify(ofrRepository).findById("OFR-001")
        verifyNoMoreInteractions(ofrRepository)
    }

    @Test
    @DisplayName("accepts a past GIN date, which is the normal case")
    fun acceptsPastDate() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr()))

        service.markGinSent("OFR-001", MarkGinSentRequest(ginDate = LocalDate.now().minusDays(3)), authToken)

        assertEquals(LocalDate.now().minusDays(3).atStartOfDay(), captureSaved().ginNotification!!.ginDate)
    }

    @Test
    @DisplayName("uses the supplied GIN date")
    fun usesSuppliedDate() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr()))

        service.markGinSent("OFR-001", MarkGinSentRequest(ginDate = LocalDate.of(2026, 8, 12)), authToken)

        assertEquals(
            LocalDate.of(2026, 8, 12).atStartOfDay(),
            captureSaved().ginNotification!!.ginDate
        )
    }

    @Test
    @DisplayName("blank note is stored as null, not as an empty string")
    fun blankNoteBecomesNull() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr()))

        service.markGinSent("OFR-001", MarkGinSentRequest(note = "   "), authToken)

        assertNull(captureSaved().ginNotification!!.note)
    }

    @Test
    @DisplayName("preserves a signed GIN copy and attachments uploaded earlier")
    fun preservesSignedCopyAndAttachments() {
        val existing = GinNotification(
            signedGINCopy = "https://storage.freighai.com/gin/signed-42.pdf",
            attachments = mutableListOf(GinAttachment("packing-list.pdf", "https://storage/pl.pdf")),
            ginDate = LocalDateTime.of(2026, 8, 1, 0, 0)
        )
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr(ginNotification = existing)))

        service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)

        val notification = captureSaved().ginNotification!!
        assertEquals("https://storage.freighai.com/gin/signed-42.pdf", notification.signedGINCopy)
        assertEquals(1, notification.attachments.size)
        assertEquals("packing-list.pdf", notification.attachments[0].fileName)
        // No date supplied, so the one already recorded must win over "today".
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), notification.ginDate)
    }

    @Test
    @DisplayName("refuses a GIN that was already sent")
    fun refusesAlreadySent() {
        val alreadySent = GinNotification(sentToCustomer = true, sentAt = LocalDateTime.now())
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr(ginNotification = alreadySent)))

        val error = assertThrows(IllegalStateException::class.java) {
            service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)
        }

        assertTrue(error.message!!.contains("already been sent"))
        verify(ofrRepository).findById("OFR-001")
        verifyNoMoreInteractions(ofrRepository)
    }

    @Test
    @DisplayName("refuses when no GIN number exists yet, which would hide the record from both stage cards")
    fun refusesWithoutGinNumber() {
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(ofr(ginNumber = null)))

        val error = assertThrows(IllegalStateException::class.java) {
            service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)
        }

        assertTrue(error.message!!.contains("no GIN number"))
        verify(ofrRepository).findById("OFR-001")
        verifyNoMoreInteractions(ofrRepository)
    }

    @Test
    @DisplayName("reports a missing fulfillment request rather than creating one")
    fun refusesMissingRecord() {
        `when`(ofrRepository.findById("OFR-404")).thenReturn(Optional.empty())

        assertThrows(IllegalArgumentException::class.java) {
            service.markGinSent("OFR-404", MarkGinSentRequest(), authToken)
        }
    }

    /**
     * The outbound pipeline stage is DERIVED, not stored. These predicates mirror
     * `CustomOrderFulfillmentRequestRepositoryImpl.getStageCriteria` — kept deliberately
     * side-by-side with it, because a change there and not here is exactly the drift that would
     * make the stat cards wrong without any test noticing.
     */
    private fun matchesGinSent(o: OrderFulfillmentRequest) =
        o.ginNumber != null && o.ginNotification?.sentToCustomer == true

    private fun matchesGinPending(o: OrderFulfillmentRequest) =
        (o.loadingTaskId != null ||
            o.statusHistory.any { it.status == FulfillmentStatus.SHIPPED && it.currentStatus }) &&
            o.ginNotification?.sentToCustomer == false

    private fun matchesReadyToDispatch(o: OrderFulfillmentRequest) =
        o.statusHistory.any { it.status == FulfillmentStatus.READY_TO_SHIP && it.currentStatus }

    @Test
    @DisplayName("a record marked from GIN Pending moves cleanly onto GIN Sent")
    fun movesFromGinPendingToGinSent() {
        val awaitingGin = ofr(
            ginNotification = GinNotification(sentToCustomer = false)
        ).copy(loadingTaskId = "TASK-LOAD-1")
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(awaitingGin))

        assertTrue(matchesGinPending(awaitingGin), "fixture must start on the GIN Pending card")

        service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)

        val saved = captureSaved()
        assertTrue(matchesGinSent(saved), "must land on GIN Sent")
        assertFalse(matchesGinPending(saved), "must leave GIN Pending")
    }

    /**
     * Documents a KNOWN PRE-EXISTING flaw rather than asserting desired behaviour.
     *
     * `READY_TO_DISPATCH` keys purely on statusHistory and says nothing about the GIN, so a record
     * marked from that card matches BOTH stages until its loading task completes, and is counted
     * on both stat cards. `sendGinEmail` behaves identically — this is not caused by marking. The
     * test exists so that if someone ever makes the stages a real partition, it fails loudly and
     * this comment gets revisited instead of the behaviour changing unnoticed.
     */
    @Test
    @DisplayName("KNOWN GAP: marking from Ready to Dispatch leaves the record on both cards")
    fun markingFromReadyToDispatchDoubleCounts() {
        val readyToDispatch = ofr().copy(
            statusHistory = mutableListOf(
                StatusHistory(status = FulfillmentStatus.READY_TO_SHIP, currentStatus = true)
            )
        )
        `when`(ofrRepository.findById("OFR-001")).thenReturn(Optional.of(readyToDispatch))

        service.markGinSent("OFR-001", MarkGinSentRequest(), authToken)

        val saved = captureSaved()
        assertTrue(matchesGinSent(saved), "lands on GIN Sent")
        assertTrue(
            matchesReadyToDispatch(saved),
            "still on Ready to Dispatch — pre-existing, and shared with the email path"
        )
    }
}
