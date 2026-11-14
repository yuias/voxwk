package app.rly3h.voxwk.translate

import app.rly3h.voxwk.data.SessionMode
import app.rly3h.voxwk.lang.AsrEngine
import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.Language.EN
import app.rly3h.voxwk.lang.Language.FR
import app.rly3h.voxwk.lang.Language.JA
import org.junit.Assert.assertEquals
import org.junit.Test

class EnginesTest {
  @Test
  fun listenFollowsTheSourceLanguage() {
    val sv = JaRecognizer.SENSEVOICE
    assertEquals(setOf(AsrEngine.SENSEVOICE), asrEnginesFor(SessionMode.LISTEN, JA, EN, FR, sv))
    assertEquals(setOf(AsrEngine.NEMOTRON), asrEnginesFor(SessionMode.LISTEN, FR, EN, FR, sv))
    assertEquals(setOf(AsrEngine.NEMOTRON), asrEnginesFor(SessionMode.LISTEN, null, JA, FR, sv))
  }

  @Test
  fun listenIgnoresTheConversationLanguages() {
    assertEquals(
      setOf(AsrEngine.NEMOTRON),
      asrEnginesFor(SessionMode.LISTEN, FR, JA, JA, JaRecognizer.SENSEVOICE),
    )
  }

  @Test
  fun conversationKeepsTheEngineBothDirectionsAgreeOn() {
    assertEquals(
      setOf(AsrEngine.SENSEVOICE),
      asrEnginesFor(SessionMode.CONVERSATION, null, JA, JA, JaRecognizer.SENSEVOICE),
    )
    assertEquals(
      setOf(AsrEngine.NEMOTRON),
      asrEnginesFor(SessionMode.CONVERSATION, null, EN, FR, JaRecognizer.SENSEVOICE),
    )
  }

  @Test
  fun conversationFallsBackToNemotronWhenDirectionsDiffer() {
    val sv = JaRecognizer.SENSEVOICE
    assertEquals(setOf(AsrEngine.NEMOTRON), asrEnginesFor(SessionMode.CONVERSATION, null, JA, EN, sv))
    assertEquals(setOf(AsrEngine.NEMOTRON), asrEnginesFor(SessionMode.CONVERSATION, null, EN, JA, sv))
  }
}
