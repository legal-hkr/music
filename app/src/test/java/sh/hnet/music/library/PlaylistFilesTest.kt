package sh.hnet.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistFilesTest {
    private fun parse(text: String, name: String = "list.m3u", charset: java.nio.charset.Charset = Charsets.UTF_8) =
        PlaylistFiles.parse(text.toByteArray(charset), name)

    @Test
    fun extendedM3uKeepsDurationArtistAndTitle() {
        val p = parse("#EXTM3U\n#EXTINF:369,Daft Punk - Get Lucky\nC:\\Music\\Daft Punk\\08 Get Lucky.flac\n#EXTGRP:x\nplain.mp3\nhttp://radio/stream\n")
        assertEquals(3, p.lines.size)
        val first = p.lines[0]
        assertEquals("C:\\Music\\Daft Punk\\08 Get Lucky.flac", first.path)
        assertEquals("Get Lucky", first.title)
        assertEquals(listOf("Daft Punk"), first.artists)
        assertEquals(369_000L, first.durationMs)
        assertNull(p.lines[1].title)
        assertEquals("http://radio/stream", p.lines[2].path)
    }

    @Test
    fun latin1FilesAreReadAsWindows1252() {
        val p = parse("#EXTM3U\n#EXTINF:10,Björk - Jóga\nBjörk/Jóga.mp3\n", charset = charset("windows-1252"))
        assertEquals("Jóga", p.lines[0].title)
        assertEquals("Björk/Jóga.mp3", p.lines[0].path)
    }

    @Test
    fun plsInNumberOrder() {
        val p = parse("[playlist]\nFile2=b.flac\nTitle2=B - Two\nFile1=a.flac\nLength1=61\nNumberOfEntries=2\nVersion=2\n", "x.pls")
        assertEquals(listOf("a.flac", "b.flac"), p.lines.map { it.path })
        assertEquals(61_000L, p.lines[0].durationMs)
        assertEquals("Two", p.lines[1].title)
        assertEquals(listOf("B"), p.lines[1].artists)
    }

    @Test
    fun xspfWithTitleAndTags() {
        val p = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
            <playlist version="1" xmlns="http://xspf.org/ns/0/"><title>Summer 2019</title><trackList>
            <track><location>Hi-Res/Daft%20Punk/08%20Get%20Lucky.flac</location><title>Get Lucky</title><creator>Daft Punk</creator><album>RAM</album><duration>369000</duration></track>
            </trackList></playlist>""",
            "s.xspf",
        )
        assertEquals("Summer 2019", p.title)
        assertEquals("Get Lucky", p.lines[0].title)
        assertEquals("RAM", p.lines[0].album)
        assertEquals(369_000L, p.lines[0].durationMs)
    }

    @Test
    fun notPlaylistsAndEmptyOnesAreRefused() {
        assertEquals(PlaylistFileException.Kind.NOT_A_PLAYLIST, assertThrows(PlaylistFileException::class.java) { parse("Dear diary, today I listened to music.\nIt was nice.") }.kind)
        assertEquals(PlaylistFileException.Kind.NOT_A_PLAYLIST, assertThrows(PlaylistFileException::class.java) { PlaylistFiles.parse(byteArrayOf(0x50, 0x4B, 0, 3), "a.m3u") }.kind)
        assertEquals(PlaylistFileException.Kind.EMPTY, assertThrows(PlaylistFileException::class.java) { parse("#EXTM3U\n# nothing\n") }.kind)
        val doctype = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><playlist xmlns="http://xspf.org/ns/0/"><trackList><track><title>&e;</title></track></trackList></playlist>"""
        assertEquals(PlaylistFileException.Kind.NOT_A_PLAYLIST, assertThrows(PlaylistFileException::class.java) { parse(doctype, "x.xspf") }.kind)
    }

    @Test
    fun everyFormatReadsBackWhatItWrote() {
        val lines = listOf(
            ExportLine("Hi-Res/Łona/Nawiasem mówiąc/03 Łódź & co.flac", "Łódź & co", listOf("Łona", "Webber"), "Nawiasem mówiąc", 241_000),
            ExportLine("C:\\Music\\Missing.flac", "Missing", emptyList(), null, null),
        )
        for (format in PlaylistFormat.entries) {
            val back = PlaylistFiles.parse(PlaylistFiles.write(format, "Wieczór <1>", lines), "x." + format.extension)
            assertEquals(format.name, 2, back.lines.size)
            assertEquals(format.name, "Łódź & co", back.lines[0].title)
            assertEquals(format.name, 241_000L, back.lines[0].durationMs)
            // The path comes back as the matcher reads it
            assertEquals(format.name, TrackMatcher.pathParts(lines[0].path), TrackMatcher.pathParts(back.lines[0].path!!))
            assertEquals(format.name, TrackMatcher.pathParts(lines[1].path), TrackMatcher.pathParts(back.lines[1].path!!))
            if (format == PlaylistFormat.XSPF) assertEquals("Wieczór <1>", back.title)
        }
    }

    @Test
    fun fileNamesLoseForbiddenCharacters() {
        assertEquals("AC_DC_ best.m3u8", PlaylistFiles.fileName("AC/DC: best", PlaylistFormat.M3U8))
    }
}
