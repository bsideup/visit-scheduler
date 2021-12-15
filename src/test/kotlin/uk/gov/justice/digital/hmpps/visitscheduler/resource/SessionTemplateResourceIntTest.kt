package uk.gov.justice.digital.hmpps.visitscheduler.resource

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.visitscheduler.config.TestClockConfiguration
import uk.gov.justice.digital.hmpps.visitscheduler.data.CreateSessionTemplateRequest
import uk.gov.justice.digital.hmpps.visitscheduler.helper.sessionTemplateCreator
import uk.gov.justice.digital.hmpps.visitscheduler.helper.sessionTemplateDeleter
import uk.gov.justice.digital.hmpps.visitscheduler.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.SessionFrequency
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.VisitType
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.repository.SessionTemplateRepository
import java.time.LocalDate
import java.time.LocalTime

@Import(TestClockConfiguration::class)
class SessionTemplateResourceIntTest : IntegrationTestBase() {

  @Autowired
  private lateinit var sessionTemplateRepository: SessionTemplateRepository

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  @AfterEach
  internal fun deleteAllSessionTemplates() = sessionTemplateDeleter(sessionTemplateRepository)

  @DisplayName("POST /session-templates")
  @Nested
  inner class CreateSessionTemplate {
    val createSessionTemplateRequest = CreateSessionTemplateRequest(
      prisonId = "LEI",
      startTime = LocalTime.of(14, 30),
      endTime = LocalTime.of(16, 30),
      startDate = LocalDate.of(2021, 1, 1),
      expiryDate = LocalDate.of(2021, 4, 1),
      visitRoom = "A1",
      visitType = VisitType.STANDARD_SOCIAL,
      frequency = SessionFrequency.WEEKLY,
      openCapacity = 5,
      closedCapacity = 2,
      restrictions = "restrictions text"
    )

    @Test
    fun `create session template`() {

      webTestClient.post().uri("/session-templates")
        .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
        .body(
          BodyInserters.fromValue(
            createSessionTemplateRequest
          )
        )
        .exchange()
        .expectStatus().isCreated
        .expectBody()
        .jsonPath("$.sessionTemplateId").isNumber
        .jsonPath("$.prisonId").isEqualTo("LEI")
        .jsonPath("$.startTime").isEqualTo("14:30:00")
        .jsonPath("$.endTime").isEqualTo("16:30:00")
        .jsonPath("$.frequency").isEqualTo("WEEKLY")
        .jsonPath("$.restrictions").isEqualTo("restrictions text")
        .jsonPath("$.openCapacity").isEqualTo(5)
        .jsonPath("$.closedCapacity").isEqualTo(2)
        .jsonPath("$.visitRoom").isEqualTo("A1")
        .jsonPath("$.visitType").isEqualTo("STANDARD_SOCIAL")
        .jsonPath("$.sessionTemplateId").isNumber
    }

    @Test
    fun `access forbidden when no role`() {

      webTestClient.post().uri("/session-templates")
        .headers(setAuthorisation(roles = listOf()))
        .body(
          BodyInserters.fromValue(
            createSessionTemplateRequest
          )
        )
        .exchange()
        .expectStatus().isForbidden
    }

    @Test
    fun `unauthorised when no token`() {

      webTestClient.post().uri("/session-templates")
        .body(
          BodyInserters.fromValue(
            createSessionTemplateRequest
          )
        )
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `create visit - invalid request`() {
      webTestClient.post().uri("/session-templates")
        .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
        .body(
          BodyInserters.fromValue(
            mapOf("wrongProperty" to "wrongValue")
          )
        )
        .exchange()
        .expectStatus().isBadRequest
    }
  }

  @DisplayName("DELETE /session-templates/{sessionTemplateId}")
  @Nested
  inner class DeleteSessionTemplateById {
    @Test
    fun `delete session template by id`() {

      val sessionTemplate = sessionTemplateCreator(sessionTemplateRepository)
        .withStartTime(LocalTime.of(10, 0))
        .withEndTime(LocalTime.of(12, 0))
        .save()

      webTestClient.delete().uri("/visits/${sessionTemplate.id}")
        .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
        .exchange()
        .expectStatus().isOk

      webTestClient.get().uri("/visits/${sessionTemplate.id}")
        .headers(setAuthorisation(roles = listOf("ROLE_VISIT_SCHEDULER")))
        .exchange()
        .expectStatus().isNotFound
    }
  }
}