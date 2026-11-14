package app.rly3h.voxwk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelDownloadTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun resumeActionFollowsTheResponseCode() {
    assertEquals(ResumeAction.Restart, resumeAction(0, 200))
    assertEquals(ResumeAction.Append, resumeAction(5, 206))
    assertEquals(ResumeAction.Restart, resumeAction(5, 200))
    assertEquals(ResumeAction.Fail(416), resumeAction(5, 416))
    assertEquals(ResumeAction.Fail(503), resumeAction(0, 503))
  }

  @Test
  fun integrityCoversTheWholeFileAfterAnAppend() {
    val first = "first chunk, already on disk".toByteArray()
    val second = "second chunk, appended after the resume".toByteArray()
    val expected = MessageDigest.getInstance("SHA-256").run {
      update(first)
      update(second)
      hex()
    }
    val part = tmp.newFile("model.gguf.part").apply { writeBytes(first) }

    val digest = MessageDigest.getInstance("SHA-256")
    hashInto(digest, part)
    digest.update(second)
    val size = (first.size + second.size).toLong()

    assertNull(integrityError("m", size, size, expected, digest.hex()))
    // A digest that skipped the existing bytes must be rejected.
    val partial = MessageDigest.getInstance("SHA-256").apply { update(second) }
    assertNotNull(integrityError("m", size, size, expected, partial.hex()))
  }

  @Test
  fun aPartWithTheExpectedSizeNeedsNoRequest() {
    assertTrue(partIsComplete(100, 100))
    assertFalse(partIsComplete(99, 100))
    assertFalse(partIsComplete(0, 0))
    assertFalse(partIsComplete(100, null))
  }
}
