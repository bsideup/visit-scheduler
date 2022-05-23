package uk.gov.justice.digital.hmpps.visitscheduler.integration

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec
import org.springframework.transaction.annotation.Propagation.SUPPORTS
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.visitscheduler.dto.CreateLegacyContactOnVisitRequestDto
import uk.gov.justice.digital.hmpps.visitscheduler.dto.CreateLegacyDataRequestDto
import uk.gov.justice.digital.hmpps.visitscheduler.dto.CreateVisitorOnVisitRequestDto
import uk.gov.justice.digital.hmpps.visitscheduler.dto.MigrateVisitRequestDto
import uk.gov.justice.digital.hmpps.visitscheduler.dto.VisitNoteDto
import uk.gov.justice.digital.hmpps.visitscheduler.helper.visitDeleter
import uk.gov.justice.digital.hmpps.visitscheduler.model.OutcomeStatus.COMPLETED_NORMALLY
import uk.gov.justice.digital.hmpps.visitscheduler.model.OutcomeStatus.NOT_RECORDED
import uk.gov.justice.digital.hmpps.visitscheduler.model.VisitNoteType.STATUS_CHANGED_REASON
import uk.gov.justice.digital.hmpps.visitscheduler.model.VisitNoteType.VISITOR_CONCERN
import uk.gov.justice.digital.hmpps.visitscheduler.model.VisitNoteType.VISIT_COMMENT
import uk.gov.justice.digital.hmpps.visitscheduler.model.VisitNoteType.VISIT_OUTCOMES
import uk.gov.justice.digital.hmpps.visitscheduler.model.VisitRestriction.OPEN
import uk.gov.justice.digital.hmpps.visitscheduler.model.VisitStatus.RESERVED
import uk.gov.justice.digital.hmpps.visitscheduler.model.VisitType.SOCIAL
import uk.gov.justice.digital.hmpps.visitscheduler.model.entity.VisitNote
import uk.gov.justice.digital.hmpps.visitscheduler.repository.LegacyDataRepository
import uk.gov.justice.digital.hmpps.visitscheduler.repository.VisitRepository
import java.time.LocalDateTime

private const val TEST_END_POINT = "/migrate-visits"

@Transactional(propagation = SUPPORTS)
@DisplayName("Migrate POST /visits")
class MigrateVisitTest : IntegrationTestBase() {

  private lateinit var roleVisitSchedulerHttpHeaders: (HttpHeaders) -> Unit

  @Autowired
  private lateinit var visitRepository: VisitRepository

  @Autowired
  private lateinit var legacyDataRepository: LegacyDataRepository

  @SpyBean
  private lateinit var telemetryClient: TelemetryClient

  @BeforeEach
  internal fun setUp() {
    roleVisitSchedulerHttpHeaders = setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER"))
  }

  @AfterEach
  internal fun deleteAllVisits() = visitDeleter(visitRepository)

  private fun createMigrateVisitRequestDto(): MigrateVisitRequestDto {
    return MigrateVisitRequestDto(
      prisonId = "MDI",
      prisonerId = "FF0000FF",
      visitRoom = "A1",
      visitType = SOCIAL,
      startTimestamp = visitTime,
      endTimestamp = visitTime.plusHours(1),
      visitStatus = RESERVED,
      outcomeStatus = COMPLETED_NORMALLY,
      visitRestriction = OPEN,
      visitContact = CreateLegacyContactOnVisitRequestDto("John Smith", "013448811538"),
      visitors = setOf(CreateVisitorOnVisitRequestDto(123)),
      visitNotes = setOf(
        VisitNoteDto(type = VISITOR_CONCERN, "A visit concern"),
        VisitNoteDto(type = VISIT_OUTCOMES, "A visit outcome"),
        VisitNoteDto(type = VISIT_COMMENT, "A visit comment"),
        VisitNoteDto(type = STATUS_CHANGED_REASON, "Status has changed")
      ),
      legacyData = CreateLegacyDataRequestDto(123)
    )
  }

  @Test
  fun `migrate visit`() {

    // Given
    val jsonBody = BodyInserters.fromValue(
      createMigrateVisitRequestDto()
    )

    // When
    val responseSpec = callMigrateVisit(roleVisitSchedulerHttpHeaders, jsonBody)

    // Then
    responseSpec.expectStatus().isCreated
    val reference = getReference(responseSpec)

    val visit = visitRepository.findByReference(reference)
    assertThat(visit).isNotNull
    visit?.let {

      assertThat(visit.reference).isEqualTo(reference)
      assertThat(visit.prisonId).isEqualTo("MDI")
      assertThat(visit.prisonerId).isEqualTo("FF0000FF")
      assertThat(visit.visitRoom).isEqualTo("A1")
      assertThat(visit.visitType).isEqualTo(SOCIAL)
      assertThat(visit.visitStart).isEqualTo(visitTime.toString())
      assertThat(visit.visitEnd).isEqualTo(visitTime.plusHours(1).toString())
      assertThat(visit.visitStatus).isEqualTo(RESERVED)
      assertThat(visit.outcomeStatus).isEqualTo(COMPLETED_NORMALLY)
      assertThat(visit.visitRestriction).isEqualTo(OPEN)
      assertThat(visit.visitContact!!.name).isEqualTo("John Smith")
      assertThat(visit.visitContact!!.telephone).isEqualTo("013448811538")
      assertThat(visit.createTimestamp).isNotNull
      assertThat(visit.visitors.size).isEqualTo(1)
      assertThat(visit.visitors[0].nomisPersonId).isEqualTo(123)
      assertThat(visit.visitNotes)
        .hasSize(4)
        .extracting(VisitNote::type, VisitNote::text)
        .containsExactlyInAnyOrder(
          tuple(VISITOR_CONCERN, "A visit concern"),
          tuple(VISIT_OUTCOMES, "A visit outcome"),
          tuple(VISIT_COMMENT, "A visit comment"),
          tuple(STATUS_CHANGED_REASON, "Status has changed")
        )

      val legacyData = legacyDataRepository.findByVisitId(visit.id)
      assertThat(legacyData).isNotNull
      assertThat(legacyData!!.visitId).isEqualTo(visit.id)
    }

    // And
    verify(telemetryClient).trackEvent(
      eq("visit-scheduler-prison-visit-migrated"),
      org.mockito.kotlin.check {
        assertThat(it["reference"]).isEqualTo(reference)
        assertThat(it["prisonerId"]).isEqualTo("FF0000FF")
        assertThat(it["prisonId"]).isEqualTo("MDI")
        assertThat(it["visitType"]).isEqualTo(SOCIAL.name)
        assertThat(it["visitRoom"]).isEqualTo("A1")
        assertThat(it["visitRestriction"]).isEqualTo(OPEN.name)
        assertThat(it["visitStart"]).isEqualTo(visitTime.toString())
        assertThat(it["visitStatus"]).isEqualTo(RESERVED.name)
        assertThat(it["outcomeStatus"]).isEqualTo(COMPLETED_NORMALLY.name)
      },
      isNull()
    )
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-migrated"), any(), isNull())
  }

  @Test
  fun `migrate visit without contact details`() {

    // Given
    val createMigrateVisitRequestDto = MigrateVisitRequestDto(
      prisonId = "MDI",
      prisonerId = "FF0000FF",
      visitRoom = "A1",
      visitType = SOCIAL,
      startTimestamp = visitTime,
      endTimestamp = visitTime.plusHours(1),
      visitStatus = RESERVED,
      visitRestriction = OPEN
    )

    val jsonBody = BodyInserters.fromValue(
      createMigrateVisitRequestDto
    )

    // When
    val responseSpec = callMigrateVisit(roleVisitSchedulerHttpHeaders, jsonBody)

    // Then
    responseSpec.expectStatus().isCreated
    val reference = getReference(responseSpec)

    val visit = visitRepository.findByReference(reference)
    assertThat(visit).isNotNull
    visit?.let {
      assertThat(visit.visitContact!!.name).isEqualTo("UNKNOWN")
      assertThat(visit.visitContact!!.telephone).isEqualTo("UNKNOWN")
    }

    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-migrated"), any(), isNull())
  }

  @Test
  fun `migrate visit when outcome status not given`() {

    // Given
    val migrateVisitRequestDto = MigrateVisitRequestDto(
      prisonId = "MDI",
      prisonerId = "FF0000FF",
      visitRoom = "A1",
      visitType = SOCIAL,
      startTimestamp = visitTime,
      endTimestamp = visitTime.plusHours(1),
      visitStatus = RESERVED,
      visitRestriction = OPEN
    )

    val jsonBody = BodyInserters.fromValue(migrateVisitRequestDto)

    // When
    val responseSpec = callMigrateVisit(roleVisitSchedulerHttpHeaders, jsonBody)

    // Then
    responseSpec.expectStatus().isCreated
    val reference = getReference(responseSpec)

    val visit = visitRepository.findByReference(reference)
    assertThat(visit).isNotNull
    visit?.let {
      assertThat(visit.outcomeStatus).isEqualTo(NOT_RECORDED)
    }

    // And
    verify(telemetryClient).trackEvent(
      eq("visit-scheduler-prison-visit-migrated"),
      org.mockito.kotlin.check {
        assertThat(it["reference"]).isEqualTo(reference)
        assertThat(it["outcomeStatus"]).isEqualTo(NOT_RECORDED.name)
      },
      isNull()
    )
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-migrated"), any(), isNull())
  }

  @Test
  fun `when telephone number is not given then an UNKNOWN will be migrated  `() {

    // Given
    val jsonString = """{
      "prisonerId": "G9377GA",
      "prisonId": "MDI",
      "visitRoom": "A1",
      "startTimestamp": "$visitTime",
      "endTimestamp": "${visitTime.plusHours(1)}",
      "visitType": "${SOCIAL.name}",
      "visitStatus": "${RESERVED.name}",
      "visitRestriction": "${OPEN.name}",
      "visitContact": {
        "name": "John Smith"
      }    
    }"""

    val responseSpec = webTestClient.post().uri(TEST_END_POINT)
      .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        BodyInserters.fromValue(
          jsonString
        )
      )
      .exchange()

    // Then
    responseSpec.expectStatus().isCreated
    val reference = getReference(responseSpec)

    val visit = visitRepository.findByReference(reference)
    assertThat(visit).isNotNull
    visit?.let {
      assertThat(visit.visitContact!!.telephone).isEqualTo("UNKNOWN")
    }

    // And
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-migrated"), any(), isNull())
  }

  @Test
  fun `when telephone number is NULL then an UNKNOWN will be migrated`() {

    // Given
    val jsonString = """{
      "prisonerId": "G9377GA",
      "prisonId": "MDI",
      "visitRoom": "A1",
      "startTimestamp": "$visitTime",
      "endTimestamp": "${visitTime.plusHours(1)}",
      "visitType": "${SOCIAL.name}",
      "visitStatus": "${RESERVED.name}",
      "visitRestriction": "${OPEN.name}",
      "visitContact": {
        "name": "John Smith",
        "telephone": null
      }    
    }"""

    val responseSpec = webTestClient.post().uri(TEST_END_POINT)
      .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        BodyInserters.fromValue(
          jsonString
        )
      )
      .exchange()

    // Then
    responseSpec.expectStatus().isCreated
    val reference = getReference(responseSpec)

    val visit = visitRepository.findByReference(reference)
    assertThat(visit).isNotNull
    visit?.let {
      assertThat(visit.visitContact!!.telephone).isEqualTo("UNKNOWN")
    }

    // And
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-migrated"), any(), isNull())
  }

  @Test
  fun `when contact name is not given then an UNKNOWN will be migrated  `() {

    // Given
    val jsonString = """{
      "prisonerId": "G9377GA",
      "prisonId": "MDI",
      "visitRoom": "A1",
      "startTimestamp": "$visitTime",
      "endTimestamp": "${visitTime.plusHours(1)}",
      "visitType": "${SOCIAL.name}",
      "visitStatus": "${RESERVED.name}",
      "visitRestriction": "${OPEN.name}",
      "visitContact": {
        "telephone": "1234567890"
      }    
    }"""

    val responseSpec = webTestClient.post().uri(TEST_END_POINT)
      .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        BodyInserters.fromValue(
          jsonString
        )
      )
      .exchange()

    // Then
    responseSpec.expectStatus().isCreated
    val reference = getReference(responseSpec)

    val visit = visitRepository.findByReference(reference)
    assertThat(visit).isNotNull
    visit?.let {
      assertThat(visit.visitContact!!.name).isEqualTo("UNKNOWN")
    }

    // And
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-migrated"), any(), isNull())
  }

  @Test
  fun `when contact name is NULL then an UNKNOWN will be migrated`() {

    // Given
    val jsonString = """{
      "prisonerId": "G9377GA",
      "prisonId": "MDI",
      "visitRoom": "A1",
      "startTimestamp": "$visitTime",
      "endTimestamp": "${visitTime.plusHours(1)}",
      "visitType": "${SOCIAL.name}",
      "visitStatus": "${RESERVED.name}",
      "visitRestriction": "${OPEN.name}",
      "visitContact": {
        "name": null,
        "telephone": "1234567890"
      }    
    }"""

    val responseSpec = webTestClient.post().uri(TEST_END_POINT)
      .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        BodyInserters.fromValue(
          jsonString
        )
      )
      .exchange()

    // Then
    responseSpec.expectStatus().isCreated
    val reference = getReference(responseSpec)

    val visit = visitRepository.findByReference(reference)
    assertThat(visit).isNotNull
    visit?.let {
      assertThat(visit.visitContact!!.name).isEqualTo("UNKNOWN")
    }

    // And
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-migrated"), any(), isNull())
  }

  @Test
  fun `migrate visit - invalid request`() {

    // Given
    val jsonBody = BodyInserters.fromValue(
      mapOf("wrongProperty" to "wrongValue")
    )

    // When
    val responseSpec = webTestClient.post().uri(TEST_END_POINT)
      .headers(roleVisitSchedulerHttpHeaders)
      .body(jsonBody)
      .exchange()

    // Then
    responseSpec.expectStatus().isBadRequest

    // And
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-bad-request-error"), any(), isNull())
  }

  @Test
  fun `access forbidden when no role`() {

    // Given
    val authHttpHeaders = setAuthorisation(roles = listOf())
    val jsonBody = BodyInserters.fromValue(createMigrateVisitRequestDto())

    // When
    val responseSpec = callMigrateVisit(authHttpHeaders, jsonBody)

    // Then
    responseSpec.expectStatus().isForbidden

    // And
    verify(telemetryClient, times(1)).trackEvent(eq("visit-scheduler-prison-visit-access-denied-error"), any(), isNull())
  }

  @Test
  fun `unauthorised when no token`() {
    // Given
    val jsonBody = BodyInserters.fromValue(createMigrateVisitRequestDto())

    // When
    val responseSpec = webTestClient.post().uri(TEST_END_POINT)
      .body(jsonBody)
      .exchange()

    // Then
    responseSpec.expectStatus().isUnauthorized
  }

  private fun getReference(responseSpec: ResponseSpec): String {
    var reference = ""
    responseSpec.expectBody()
      .jsonPath("$")
      .value<String> { json -> reference = json }
    return reference
  }

  private fun callMigrateVisit(
    authHttpHeaders: (HttpHeaders) -> Unit,
    jsonBody: BodyInserter<*, in ClientHttpRequest>?
  ): ResponseSpec {
    return webTestClient.post().uri(TEST_END_POINT)
      .headers(authHttpHeaders)
      .body(jsonBody)
      .exchange()
  }

  companion object {
    val visitTime: LocalDateTime = LocalDateTime.of(2021, 11, 1, 12, 30, 44)
  }
}
