package app.rly3h.voxwk.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouterTest {
  @Test
  fun wiredHeadsetBeatsBluetoothSco() {
    val types = listOf(
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
      AudioDeviceInfo.TYPE_WIRED_HEADSET,
    )
    assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, pickEarphoneType(types))
  }

  @Test
  fun builtinOutputsAreNotEarphones() {
    val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    assertNull(pickEarphoneType(types))
  }

  @Test
  fun bleHeadsetBeatsBluetoothSco() {
    val types = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, pickEarphoneType(types))
  }

  @Test
  fun usbHeadsetBeatsBluetoothSco() {
    val types = listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, pickEarphoneType(types))
  }

  @Test
  fun emptyListHasNoEarphone() {
    assertNull(pickEarphoneType(emptyList()))
  }

  @Test
  fun builtinSpeakerAloneIsNotPrivateForListen() {
    assertFalse(listenHasPrivateOutput(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
  }

  @Test
  fun a2dpMakesListenOutputPrivate() {
    val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    assertTrue(listenHasPrivateOutput(types))
  }

  @Test
  fun emptyOutputListIsNotPrivateForListen() {
    assertFalse(listenHasPrivateOutput(emptyList()))
  }

  private val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
  private val wired = AudioDeviceInfo.TYPE_WIRED_HEADSET

  @Test
  fun scoIsGlassInGlassSessionEvenWhenWiredHeadsetIsPresent() {
    val types = listOf(wired, sco)
    assertEquals(MeDevice(1, ResolvedOutput.GLASS), pickMeDevice(types, glassSession = true))
  }

  @Test
  fun scoIsEarphoneOutsideGlassSession() {
    val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sco)
    assertEquals(MeDevice(1, ResolvedOutput.EARPHONE), pickMeDevice(types, glassSession = false))
  }

  @Test
  fun glassSessionWithoutScoFallsBackToEarphoneOrder() {
    val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, wired)
    assertEquals(MeDevice(1, ResolvedOutput.EARPHONE), pickMeDevice(types, glassSession = true))
  }

  @Test
  fun noCommunicationDeviceGivesNoMeDevice() {
    assertNull(pickMeDevice(emptyList(), glassSession = true))
    assertNull(pickMeDevice(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), glassSession = true))
  }

  @Test
  fun glassOutputHoldsTheEchoGateLongerThanSpeaker() {
    assertEquals(GLASS_ECHO_TAIL_MS, echoTailMs(ResolvedOutput.GLASS))
    assertEquals(ECHO_TAIL_MS, echoTailMs(ResolvedOutput.SPEAKER))
  }
}
