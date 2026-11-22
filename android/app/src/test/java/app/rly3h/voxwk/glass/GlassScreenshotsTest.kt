package app.rly3h.voxwk.glass

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class GlassScreenshotsTest {
  @Test
  fun fileNameUsesTheGivenZone() {
    assertEquals("voxwk-glass-19700101-000000.png", screenshotFileName(0, ZoneOffset.UTC))
    assertEquals("voxwk-glass-19700101-090000.png", screenshotFileName(0, ZoneOffset.ofHours(9)))
  }
}
