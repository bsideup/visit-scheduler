package uk.gov.justice.digital.hmpps.visitscheduler.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.visitscheduler.data.CreateVisitRequest
import uk.gov.justice.digital.hmpps.visitscheduler.data.UpdateVisitRequest
import uk.gov.justice.digital.hmpps.visitscheduler.data.VisitDto
import uk.gov.justice.digital.hmpps.visitscheduler.data.filter.VisitFilter
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.LegacyData
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.Visit
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.VisitContact
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.VisitNote
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.VisitNoteType
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.VisitSupport
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.VisitVisitor
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.repository.LegacyDataRepository
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.repository.SupportTypeRepository
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.repository.VisitRepository
import uk.gov.justice.digital.hmpps.visitscheduler.jpa.specification.VisitSpecification
import java.util.function.Supplier

@Service
@Transactional
class VisitService(
  private val legacyDataRepository: LegacyDataRepository,
  private val visitRepository: VisitRepository,
  private val supportTypeRepository: SupportTypeRepository,
) {

  fun createVisit(createVisitRequest: CreateVisitRequest): VisitDto {
    log.info("Creating visit for prisoner")
    val visitEntity = visitRepository.saveAndFlush(
      Visit(
        prisonerId = createVisitRequest.prisonerId,
        prisonId = createVisitRequest.prisonId,
        visitRoom = createVisitRequest.visitRoom,
        visitType = createVisitRequest.visitType,
        visitStatus = createVisitRequest.visitStatus,
        visitRestriction = createVisitRequest.visitRestriction,
        visitStart = createVisitRequest.startTimestamp,
        visitEnd = createVisitRequest.endTimestamp
      )
    )

    createVisitRequest.visitContact?.let {
      visitEntity.visitContact = createVisitContact(visitEntity, it.name, it.telephone)
    }

    createVisitRequest.visitors?.let { contactList ->
      contactList.distinctBy { it.nomisPersonId }.forEach {
        visitEntity.visitors.add(createVisitVisitor(visitEntity, it.nomisPersonId))
      }
    }

    createVisitRequest.visitorSupport?.let { supportList ->
      supportList.distinctBy { it.type }.forEach {
        supportTypeRepository.findByName(it.type) ?: throw SupportNotFoundException("Invalid support ${it.type} not found")
        visitEntity.support.add(createVisitSupport(visitEntity, it.type, it.text))
      }
    }

    createVisitRequest.visitNotes?.let { visitNotes ->
      visitNotes.distinctBy { it.type }.forEach {
        visitEntity.visitNotes.add(createVisitNote(visitEntity, it.type, it.text))
      }
    }

    createVisitRequest.legacyData?.let {
      saveLegacyData(visitEntity, it.leadVisitorId)
    }

    return VisitDto(visitEntity)
  }

  @Transactional(readOnly = true)
  fun findVisitsByFilter(visitFilter: VisitFilter): List<VisitDto> {
    return visitRepository.findAll(VisitSpecification(visitFilter)).sortedBy { it.visitStart }.map { VisitDto(it) }
  }

  @Transactional(readOnly = true)
  fun getVisitByReference(reference: String): VisitDto {
    return VisitDto(visitRepository.findByReference(reference) ?: throw VisitNotFoundException("Visit reference $reference not found"))
  }

  fun updateVisit(reference: String, updateVisitRequest: UpdateVisitRequest): VisitDto {
    log.info("Updating visit for $reference")

    val visitEntity = visitRepository.findByReference(reference)
    visitEntity ?: throw VisitNotFoundException("Visit reference $reference not found")

    updateVisitRequest.prisonerId?.let { prisonerId -> visitEntity.prisonerId = prisonerId }
    updateVisitRequest.prisonId?.let { prisonId -> visitEntity.prisonId = prisonId }
    updateVisitRequest.visitRoom?.let { visitRoom -> visitEntity.visitRoom = visitRoom }
    updateVisitRequest.visitType?.let { visitType -> visitEntity.visitType = visitType }
    updateVisitRequest.visitStatus?.let { status -> visitEntity.visitStatus = status }
    updateVisitRequest.visitRestriction?.let { visitRestriction -> visitEntity.visitRestriction = visitRestriction }
    updateVisitRequest.startTimestamp?.let { visitStart -> visitEntity.visitStart = visitStart }
    updateVisitRequest.endTimestamp?.let { visitEnd -> visitEntity.visitEnd = visitEnd }

    // Update existing or add new
    updateVisitRequest.visitContact?.let { visitContactUpdate ->
      visitEntity.visitContact?.let { visitContact ->
        visitContact.name = visitContactUpdate.name
        visitContact.telephone = visitContactUpdate.telephone
      } ?: run {
        visitEntity.visitContact = createVisitContact(visitEntity, visitContactUpdate.name, visitContactUpdate.telephone)
      }
    }

    // Replace existing list
    updateVisitRequest.visitors?.let { visitorsUpdate ->
      visitEntity.visitors.clear()
      visitRepository.saveAndFlush(visitEntity)
      visitorsUpdate.distinctBy { it.nomisPersonId }.forEach {
        visitEntity.visitors.add(createVisitVisitor(visitEntity, it.nomisPersonId))
      }
    }

    // Replace existing list
    updateVisitRequest.visitorSupport?.let { visitSupportUpdate ->
      visitEntity.support.clear()
      visitRepository.saveAndFlush(visitEntity)
      visitSupportUpdate.distinctBy { it.type }.forEach {
        supportTypeRepository.findByName(it.type) ?: throw SupportNotFoundException("Invalid support ${it.type} not found")
        visitEntity.support.add(createVisitSupport(visitEntity, it.type, it.text))
      }
    }

    // visitRepository.saveAndFlush(visitEntity)

    return VisitDto(visitEntity)
  }

  fun deleteVisit(reference: String) {
    val visit = visitRepository.findByReference(reference)
    visit?.let { visitRepository.delete(it) }.also { log.info("Visit with reference $reference deleted") }
      ?: run {
        log.info("Visit reference $reference not found")
      }
  }

  fun deleteAllVisits(visits: List<VisitDto>) {
    visitRepository.deleteAllByReferenceIn(visits.map { it.reference }.toList())
  }

  private fun createVisitNote(visit: Visit, type: VisitNoteType, text: String): VisitNote {
    return VisitNote(
      visitId = visit.id,
      type = type,
      text = text,
      visit = visit
    )
  }

  private fun saveLegacyData(visit: Visit, leadPersonId: Long) {
    val legacyData = LegacyData(
      visitId = visit.id,
      leadPersonId = leadPersonId
    )

    legacyDataRepository.saveAndFlush(legacyData)
  }

  private fun createVisitContact(visit: Visit, name: String, telephone: String): VisitContact {
    return VisitContact(
      visitId = visit.id,
      name = name,
      telephone = telephone,
      visit = visit
    )
  }

  private fun createVisitVisitor(visit: Visit, personId: Long): VisitVisitor {
    return VisitVisitor(
      nomisPersonId = personId,
      visitId = visit.id,
      visit = visit
    )
  }

  private fun createVisitSupport(visit: Visit, type: String, text: String?): VisitSupport {
    return VisitSupport(
      type = type,
      visitId = visit.id,
      text = text,
      visit = visit
    )
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

class VisitNotFoundException(message: String? = null, cause: Throwable? = null) :
  RuntimeException(message, cause),
  Supplier<VisitNotFoundException> {
  override fun get(): VisitNotFoundException {
    return VisitNotFoundException(message, cause)
  }
}

class SupportNotFoundException(message: String? = null, cause: Throwable? = null) :
  RuntimeException(message, cause),
  Supplier<SupportNotFoundException> {
  override fun get(): SupportNotFoundException {
    return SupportNotFoundException(message, cause)
  }
}