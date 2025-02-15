package uk.gov.justice.digital.hmpps.visitscheduler.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.visitscheduler.dto.CreateSessionTemplateRequestDto
import uk.gov.justice.digital.hmpps.visitscheduler.dto.SessionTemplateDto
import uk.gov.justice.digital.hmpps.visitscheduler.model.entity.SessionTemplate
import uk.gov.justice.digital.hmpps.visitscheduler.repository.SessionTemplateRepository
import java.util.function.Supplier

@Service
@Transactional
class SessionTemplateService(
  private val sessionTemplateRepository: SessionTemplateRepository,
  private val telemetryClient: TelemetryClient,
) {

  fun createSessionTemplate(createSessionTemplateRequest: CreateSessionTemplateRequestDto): SessionTemplateDto {
    val sessionTemplateEntity = sessionTemplateRepository.saveAndFlush(
      SessionTemplate(
        prisonId = createSessionTemplateRequest.prisonId,
        startTime = createSessionTemplateRequest.startTime,
        endTime = createSessionTemplateRequest.endTime,
        visitType = createSessionTemplateRequest.visitType,
        validFromDate = createSessionTemplateRequest.validFromDate,
        validToDate = createSessionTemplateRequest.validToDate,
        visitRoom = createSessionTemplateRequest.visitRoom,
        closedCapacity = createSessionTemplateRequest.closedCapacity,
        openCapacity = createSessionTemplateRequest.openCapacity,
        dayOfWeek = createSessionTemplateRequest.dayOfWeek
      )
    )

    telemetryClient.trackEvent(
      TelemetryVisitEvents.SESSION_TEMPLATE_CREATED.eventName,
      mapOf("id" to sessionTemplateEntity.id.toString()),
      null
    )

    return SessionTemplateDto(sessionTemplateEntity)
  }

  fun getSessionTemplates(): List<SessionTemplateDto> {
    return sessionTemplateRepository.findAll().sortedBy { it.validFromDate }.map { SessionTemplateDto(it) }
  }

  fun getSessionTemplates(sessionTemplateId: Long): SessionTemplateDto {
    return sessionTemplateRepository.findById(sessionTemplateId).map { SessionTemplateDto(it) }
      .orElseThrow(TemplateNotFoundException("Template id $sessionTemplateId not found"))
  }

  fun deleteSessionTemplate(templateId: Long) {
    val template = sessionTemplateRepository.findByIdOrNull(templateId)
    template?.let {
      sessionTemplateRepository.delete(it)
      telemetryClient.trackEvent(
        TelemetryVisitEvents.SESSION_TEMPLATE_DELETED.eventName,
        mapOf("id" to templateId.toString()),
        null
      )
    } ?: run { log.debug("Session template $templateId not found") }
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

class TemplateNotFoundException(message: String? = null, cause: Throwable? = null) :
  RuntimeException(message, cause),
  Supplier<TemplateNotFoundException> {
  override fun get(): TemplateNotFoundException {
    return TemplateNotFoundException(message, cause)
  }
}
