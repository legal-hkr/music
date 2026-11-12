package sh.hnet.music.backup

import org.junit.Assert.assertFalse
import sh.hnet.music.playback.FilterType
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.EqFilter
import sh.hnet.music.equalizer.EqualizerState
import sh.hnet.music.equalizer.BuiltInPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.music.playback.OutputKind
import sh.hnet.music.playback.AudioOutput
import sh.hnet.music.output.DevicesState
import sh.hnet.music.equalizer.PresetRef

class BackupTest {
    private val sample = BackupFile(
        appVersion = "0.1.0",
        appVersionCode = 1,
        createdAt = "2026-11-09T14:20:05Z",
        settings = BackupSettings(
            sorts = BackupSettings.Sorts(albums = "ALBUM_ARTIST", tracks = "TITLE", playlists = "RECENTLY_CHANGED"),
            libraryViews = BackupSettings.LibraryViews(order = listOf("ALBUMS", "PLAYLISTS", "ARTISTS", "TRACKS"), opensOn = "LAST_USED"),
            playback = BackupSettings.Playback(replayGain = "ALBUM", preampDb = -6f, preventClipping = true, dsdPcmRate = 176_400),
            exportFormat = "M3U8",
            skipShorterThanSeconds = 30,
        ),
        playlists = listOf(
            BackupPlaylist(
                name = "Starred",
                builtIn = BackupPlaylist.STARRED,
                entries = listOf(BackupEntry("Hi-Res", "Daft Punk/RAM/08 Get Lucky.flac", "Get Lucky", listOf("Daft Punk", "Pharrell Williams"), "Random Access Memories", 369000)),
            ),
            BackupPlaylist(name = "Lato", entries = listOf(BackupEntry(path = "a.mp3"), BackupEntry(path = "b.mp3"))),
        ),
    )

    @Test
    fun `a backup reads back as it was written`() {
        val check = Backup.check(Backup.encode(sample))
        assertTrue(check is BackupCheck.Valid)
        check as BackupCheck.Valid
        assertEquals(sample, check.backup)
        assertEquals(2, check.playlists)
        assertEquals(3, check.tracks)
        assertTrue(check.hasStarred)
    }

    @Test
    fun `an empty Starred isn't counted as a playlist`() {
        val check = Backup.check(Backup.encode(sample.copy(playlists = listOf(BackupPlaylist("Starred", BackupPlaylist.STARRED)))))
        check as BackupCheck.Valid
        assertEquals(0, check.playlists)
        assertEquals(false, check.hasStarred)
    }

    @Test
    fun `other files are not ours`() {
        assertTrue(Backup.check("not json") is BackupCheck.NotOurs)
        assertTrue(Backup.check("[1, 2]") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format": "sh.hnet.comfychair.backup", "formatVersion": 1}""") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format": "sh.hnet.music.backup"}""") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format": "sh.hnet.music.backup", "formatVersion": 1, "playlists": 3}""") is BackupCheck.NotOurs)
    }

    @Test
    fun `a newer format is refused, unknown fields of this one are ignored`() {
        assertEquals(BackupCheck.Newer(2), Backup.check("""{"format": "sh.hnet.music.backup", "formatVersion": 2}"""))
        val withExtra = Backup.encode(sample).replaceFirst("{", """{"futureField": {"x": 1},""")
        assertTrue(Backup.check(withExtra) is BackupCheck.Valid)
    }

    @Test
    fun `the equalizer travels in a backup and comes back within its limits`() {
        val mine = EqSettings(-3f, listOf(EqFilter(type = FilterType.LOW_SHELF, frequencyHz = 105, gainDb = 6.3f, q = 0.7f), EqFilter(enabled = false)))
        val state = EqualizerState(enabled = true).withBuiltIn(BuiltInPresets.AUDEZE_ISINE_10).savedAsNew("Mine", 1_000, mine).withPreamp(-4f)
        val key = { name: String -> name.trim().lowercase() }
        val file = BackupFile(
            appVersion = "0.2.0", appVersionCode = 2, createdAt = "2026-11-10T10:00:00Z",
            settings = BackupSettings(equalizer = EqualizerBackup.settings(state)), playlists = emptyList(), equalizerPresets = EqualizerBackup.presets(state),
        )
        val read = (Backup.check(Backup.encode(file)) as BackupCheck.Valid)
        assertTrue(read.hasEqualizer)
        assertEquals(1, read.presets)
        assertEquals(state, EqualizerBackup.restore(read.backup.settings.equalizer!!, read.backup.equalizerPresets!!, 5, key))

        // Out of range, an unknown type, a taken name, a built-in preset this version doesn't have
        val odd = EqualizerBackup.restore(
            BackupEqualizer(
                enabled = true,
                presetRef = BackupPresetRef(builtIn = "from_the_future"),
                current = BackupEqValues(-99f, listOf(BackupEqFilter(type = "NOTCH"), BackupEqFilter(frequencyHz = 5, gainDb = 40f, q = 0f))),
            ),
            listOf(BackupEqPreset("Evening"), BackupEqPreset("evening"), BackupEqPreset("Audeze iSine 10"), BackupEqPreset(" ")),
            5, key,
        )
        assertEquals(null, odd.preset)
        assertEquals(-20f, odd.current.preampDb, 0f)
        assertEquals(listOf(EqFilter(frequencyHz = 20, gainDb = 20f, q = 0.1f)), odd.current.filters)
        assertEquals(listOf("Evening", "evening (2)", "Audeze iSine 10 (2)"), odd.presets.map { it.name })
        assertEquals(5L, odd.presets.first().changedAt)
    }

    @Test
    fun `a backup from before the equalizer has none`() {
        val read = Backup.check("""{"format":"sh.hnet.music.backup","formatVersion":1,"appVersion":"0.1.0","appVersionCode":1,"createdAt":"2026-01-01T00:00:00Z","settings":{},"playlists":[]}""") as BackupCheck.Valid
        assertFalse(read.hasEqualizer)
        assertEquals(0, read.presets)
    }

    @Test
    fun `devices travel in a backup with their bit-perfect switch and their own preset`() {
        val key = { name: String -> name.trim().lowercase() }
        val dac = AudioOutput(OutputKind.USB, "HiBy R4")
        val buds = AudioOutput(OutputKind.BLUETOOTH, "Beoplay EX")
        val speaker = AudioOutput(OutputKind.SPEAKER, "")
        val devices = DevicesState().played(speaker, 1_000).played(buds, 2_000).played(dac, 3_000).withBitPerfect(dac, true)
        val isine = PresetRef(builtIn = BuiltInPresets.AUDEZE_ISINE_10.id)
        val state = EqualizerState(enabled = true)
            .withDevice(buds.key).savedAsNew("Mine", 1_000, EqSettings(preampDb = -3f)).withPreamp(-4f)
            .assigned(dac.key, isine).withOther(isine)
        val file = BackupFile(
            appVersion = "0.3.0", appVersionCode = 4, createdAt = "2026-11-12T10:00:00Z",
            settings = BackupSettings(equalizer = EqualizerBackup.settings(state), output = EqualizerBackup.devices(devices, state)),
            playlists = emptyList(), equalizerPresets = EqualizerBackup.presets(state),
        )
        val read = (Backup.check(Backup.encode(file)) as BackupCheck.Valid).backup
        assertEquals(devices, EqualizerBackup.restoreDevices(read.settings.output!!))
        val restored = EqualizerBackup.restore(read.settings.equalizer!!, read.equalizerPresets!!, 5, key, read.settings.output)
        // Which device plays now is no part of a backup
        assertEquals(state.withDevice(null), restored)
        assertEquals(-4f, restored.withDevice(buds.key).applied!!.preampDb, 0f)
        assertEquals(isine, restored.refFor(speaker.key))

        // A preset that isn't there leaves the device on the slot's preset and the slot on Flat; an unknown kind is left out
        val odd = BackupOutput(listOf(BackupDevice("USB", "DAC", bitPerfect = true, presetRef = BackupPresetRef(own = "Gone")), BackupDevice("HOLOGRAM", "X"), BackupDevice("BLUETOOTH", "Buds", bitPerfect = true)))
        val oddState = EqualizerBackup.restore(BackupEqualizer(otherDevicesPresetRef = BackupPresetRef(builtIn = "from_the_future"), presetRef = BackupPresetRef(builtIn = "flat")), emptyList(), 5, key, odd)
        assertEquals(PresetRef.FLAT, oddState.refFor("usb:DAC"))
        assertTrue(oddState.assigned.isEmpty())
        val oddDevices = EqualizerBackup.restoreDevices(odd)
        assertEquals(listOf("usb:DAC", "bluetooth:Buds"), oddDevices.devices.map { it.key })
        assertEquals(setOf("usb:DAC"), oddDevices.bitPerfectKeys)

        // Restore defaults keeps the devices, bit-perfect off; an older backup's one switch is every USB device's
        assertEquals(emptySet<String>(), devices.withoutBitPerfect().bitPerfectKeys)
        assertEquals(3, devices.withoutBitPerfect().devices.size)
        assertEquals(setOf(dac.key), devices.withoutBitPerfect().withBitPerfectEverywhere(true).bitPerfectKeys)
    }
}
