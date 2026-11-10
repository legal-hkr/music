package sh.hnet.music.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
