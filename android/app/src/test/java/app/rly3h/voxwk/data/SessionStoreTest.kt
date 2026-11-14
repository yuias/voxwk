package app.rly3h.voxwk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTest {
  @Test
  fun roundTripKeepsEveryField() {
    val log = SessionLog(
      id = "1700000000000",
      startedAt = 1700000000000,
      mode = "CONVERSATION",
      input = "GLASSES",
      utterances = listOf(
        LoggedUtterance("hello", "en", "ja", "en", "konnichiwa", "PARTNER", 1700000001234),
        LoggedUtterance("thanks", null, "ja", "", "", null, 1700000005678),
      ),
    )
    val decoded = SessionStore.decode(SessionStore.encode(log))
    assertEquals(log, decoded)
    assertEquals("GLASSES", decoded?.input)
    assertEquals(1700000001234, decoded?.utterances?.first()?.at)
    assertNull(decoded?.utterances?.last()?.srcLang)
  }

  @Test
  fun fileMissingInputDecodesToNull() {
    val text = """{"id":"1","startedAt":1,"mode":"LISTEN","utterances":[]}"""
    assertNull(SessionStore.decode(text))
  }

  @Test
  fun garbageDecodesToNull() {
    assertNull(SessionStore.decode("not json"))
  }
}
