package uk.gov.justice.digital.hmpps.visitscheduler.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class QuotableEncoderTest {

  private val encoderDefault = QuotableEncoder()

  @Nested
  @DisplayName("Default Encoder")
  inner class DefaultEncoder {

    @Test
    fun `Default encoder produces known encoding`() {
      val input = 123456L
      val knownEncoding = "2q-r6"

      val encoded = encoderDefault.encode(input)

      assertThat(input).isNotEqualTo(encoded)
      assertThat(encoded).isEqualTo(knownEncoding)
    }

    @Test
    fun `Default encoder produces known decoding`() {
      val input = "2q-r6"
      val knownDecoding = 123456L

      val decoded = encoderDefault.decode(input)

      assertThat(input).isNotEqualTo(decoded)
      assertThat(decoded).isEqualTo(knownDecoding)
    }

    @Test
    fun `encoded values are decoded`() {
      val input = 654321L

      val encoded = encoderDefault.encode(input)
      val decoded = encoderDefault.decode(encoded)

      assertThat(input).isNotEqualTo(encoded)
      assertThat(encoded).isNotEqualTo(decoded)
      assertThat(input).isEqualTo(decoded)
    }
  }

  @Nested
  @DisplayName("Encoder Parameters")
  inner class EncoderParameters {

    @Test
    fun `empty delimiters are permitted`() {
      val encoder = QuotableEncoder(delimiter = "", minLength = 2, chunkSize = 1)

      val input = 1L
      val encoded = encoder.encode(input)

      assertThat(encoded.length).isEqualTo(2)
    }

    @Test
    fun `encoded length is at least one chunk size`() {
      val encoder = QuotableEncoder(minLength = 3, chunkSize = 10)

      val input = 1L
      val encoded = encoder.encode(input)

      assertThat(encoded.length).isEqualTo(10)
    }

    @Test
    fun `encoded length is multiple of chunk size`() {
      val encoder = QuotableEncoder(minLength = 5, chunkSize = 2)

      val input = 1L
      val encoded = encoder.encode(input)
      println(encoded)

      assertThat(encoded.length % 2).isEqualTo(0)
    }

    @Test
    fun `minimum length must be greater than zero throws IllegalArgumentException`() {
      assertThrows<IllegalArgumentException> {
        QuotableEncoder(minLength = 0)
      }
    }

    @Test
    fun `minimum chunk size must be greater than zero throws IllegalArgumentException`() {
      assertThrows<IllegalArgumentException> {
        QuotableEncoder(chunkSize = 0)
      }
    }

    @Test
    fun `alpha delimiter throws IllegalArgumentException`() {
      assertThrows<IllegalArgumentException> {
        QuotableEncoder(delimiter = "a")
      }
    }

    @Test
    fun `numeric delimiter throws IllegalArgumentException`() {
      assertThrows<IllegalArgumentException> {
        QuotableEncoder(delimiter = "1")
      }
    }

    @Test
    fun `delimiter length is too long throws IllegalArgumentException`() {
      assertThrows<IllegalArgumentException> {
        QuotableEncoder(delimiter = "##")
      }
    }
  }

  @Nested
  @DisplayName("Encoder Collisions")
  inner class EncoderCollisions {

    @Test
    fun `One million hashes has no collisions`() {
      // Not designed for testing extremely large values.
      val encoder = QuotableEncoder(minLength = 8)

      val hashes = mutableListOf<String>()
      for (n in 0..1000000) {
        val hash = encoder.encode(n.toLong())
        hashes.add(hash)
      }
      val collisions = hashes.groupingBy { it }.eachCount().filter { it.value > 1 }

      assert(collisions.isEmpty())
    }
  }
}