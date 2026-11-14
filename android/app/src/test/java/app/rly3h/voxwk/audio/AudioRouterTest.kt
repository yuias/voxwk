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
}
