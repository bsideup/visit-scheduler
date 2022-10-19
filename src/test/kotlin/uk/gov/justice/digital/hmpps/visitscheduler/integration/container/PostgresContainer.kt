package uk.gov.justice.digital.hmpps.visitscheduler.integration.container

import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer

object PostgresContainer {
  private val log = LoggerFactory.getLogger(this::class.java)
  val instance: PostgreSQLContainer<Nothing>? by lazy { startPostgresqlIfNotRunning() }
  private fun startPostgresqlIfNotRunning(): PostgreSQLContainer<Nothing>? {
    val logConsumer = Slf4jLogConsumer(log).withPrefix("postgresql")

    return PostgreSQLContainer<Nothing>("postgres:13.2").apply {
      withDatabaseName("visit_scheduler")
      withUsername("visit_scheduler")
      withPassword("visit_scheduler")
      withReuse(true)
      withLogConsumer(logConsumer)
      start()
    }
  }
}
