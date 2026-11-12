package sh.hnet.music.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentOutputTest {
    @Test
    fun `the speaker and the headphone jack have no name of their own`() {
        assertEquals(AudioOutput(OutputKind.SPEAKER, ""), CurrentOutput.output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 9 Pro"))
        assertEquals(AudioOutput(OutputKind.WIRED, ""), CurrentOutput.output(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Pixel 9 Pro"))
        assertEquals("speaker", CurrentOutput.output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 9 Pro").key)
        assertEquals("wired", CurrentOutput.output(AudioDeviceInfo.TYPE_WIRED_HEADSET, null).key)
    }

    @Test
    fun `USB and Bluetooth devices are told apart by name`() {
        assertEquals("usb:HiBy R4", CurrentOutput.output(AudioDeviceInfo.TYPE_USB_DEVICE, " HiBy R4 ").key)
        assertEquals("usb:HiBy R4", CurrentOutput.output(AudioDeviceInfo.TYPE_USB_HEADSET, "HiBy R4").key)
        assertEquals("bluetooth:WH-1000XM5", CurrentOutput.output(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM5").key)
        assertEquals("bluetooth:WH-1000XM5", CurrentOutput.output(AudioDeviceInfo.TYPE_BLE_HEADSET, "WH-1000XM5").key)
        assertEquals("usb:", CurrentOutput.output(AudioDeviceInfo.TYPE_USB_DEVICE, null).key)
    }

    @Test
    fun `anything else is another output under its name`() {
        assertEquals(AudioOutput(OutputKind.OTHER, "TV"), CurrentOutput.output(AudioDeviceInfo.TYPE_HDMI, "TV"))
    }
}
