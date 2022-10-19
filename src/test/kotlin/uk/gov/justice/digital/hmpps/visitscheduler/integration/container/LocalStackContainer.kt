package uk.gov.justice.digital.hmpps.visitscheduler.integration.container

import org.slf4j.LoggerFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.utility.DockerImageName

object LocalStackContainer {
  private val log = LoggerFactory.getLogger(this::class.java)
  val instance by lazy { startLocalstackIfNotRunning() }

  private fun startLocalstackIfNotRunning(): LocalStackContainer? {
    val logConsumer = Slf4jLogConsumer(log).withPrefix("localstack")

    return LocalStackContainer(
      DockerImageName.parse("localstack/localstack").withTag("0.12.10")
    ).apply {
      withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS)
      withEnv("DEFAULT_REGION", "eu-west-2")
      withLogConsumer(logConsumer)
    }
  }
}
