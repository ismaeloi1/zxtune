package app.zxtune.ui.about

/**
 * Free and open source components used by application, shown at About dialog.
 *
 * Native components are taken from 3rdparty/ directory (see [dir], license is from its license file),
 * keep in sync when adding new ones (checked by unit test).
 */
object OpenSourceComponents {
    enum class Category {
        PLAYBACK,
        CODECS,
        SYSTEM,
        ANDROID,
        REFERENCE,
    }

    data class Component(
        val name: String,
        val authors: String,
        val license: String,
        val url: String,
        val category: Category,
        // 3rdparty/ subdirectory if any
        val dir: String? = null,
    )

    private fun playback(name: String, authors: String, license: String, url: String, dir: String? = null) = Component(name, authors, license, url, Category.PLAYBACK, dir)

    private fun codec(name: String, authors: String, license: String, url: String, dir: String? = null) = Component(name, authors, license, url, Category.CODECS, dir)

    private fun system(name: String, authors: String, license: String, url: String, dir: String? = null) = Component(name, authors, license, url, Category.SYSTEM, dir)

    private fun android(name: String, authors: String, license: String, url: String) = Component(name, authors, license, url, Category.ANDROID)

    private fun reference(name: String, authors: String, license: String, url: String) = Component(name, authors, license, url, Category.REFERENCE)

    val ALL = listOf(
        playback("ZXTune", "Vitamin/CAIG", "GPL-3.0", "https://github.com/vitamin-caig/zxtune"),
        playback(
            "libsidplayfp, reSID, reSIDfp",
            "Leandro Nini, Antti Lankila, Dag Lem et al.",
            "GPL-2.0+",
            "https://github.com/libsidplayfp/libsidplayfp",
            "sidplayfp",
        ),
        playback(
            "SIDId and sidid.cfg signatures",
            "Cadaver, HVSC crew et al.",
            "BSD-3-Clause",
            "https://github.com/cadaver/sidid",
            "sidid",
        ),
        playback("libvgm", "Valley Bell et al.", "GPL-2.0, BSD, MAME (per core)", "https://github.com/ValleyBell/libvgm", "vgm"),
        playback(
            "Game_Music_Emu",
            "Shay Green (blargg), Chris Moeller et al.",
            "LGPL-2.1+",
            "https://github.com/libgme/game-music-emu",
            "gme",
        ),
        playback("snes_spc", "Shay Green (blargg)", "LGPL-2.1+", "https://www.slack.net/~ant/libs/audio.html", "snesspc"),
        playback("Munt (mt32emu)", "Munt team", "LGPL-2.1+", "https://github.com/munt/munt", "mt32emu"),
        playback("libopenmpt", "OpenMPT developers", "BSD-3-Clause", "https://lib.openmpt.org", "openmpt"),
        playback("libxmp", "Claudio Matsuoka, Hipolito Carraro Jr et al.", "MIT", "https://github.com/libxmp/libxmp", "xmp"),
        playback("ASAP", "Piotr Fusik", "GPL-2.0+", "https://asap.sourceforge.net", "asap"),
        playback("vgmstream", "vgmstream team", "ISC", "https://github.com/vgmstream/vgmstream", "vgmstream"),
        playback("Highly Experimental", "Neill Corlett", "GPL-3.0", "https://github.com/kode54/Highly_Experimental", "he"),
        playback(
            "Highly Theoretical",
            "Neill Corlett, Christopher Snowhill",
            "GPL-3.0",
            "https://github.com/kode54/Highly_Theoretical",
            "ht",
        ),
        playback("lazyusf2", "Christopher Snowhill", "GPL-2.0 (Mupen64Plus)", "https://github.com/kode54/lazyusf2", "lazyusf2"),
        playback("mGBA", "Jeffrey Pfau", "MPL-2.0", "https://mgba.io", "mgba"),
        playback("vio2sf", "Christopher Snowhill", "GPL-2.0 (DeSmuME)", "https://github.com/kode54/vio2sf", "vio2sf"),
        playback("sseqplayer", "Naram Qashat, Christopher Snowhill", "WTFPL", "https://github.com/kode54/sseqplayer", "sseqplayer"),
        playback("HivelyTracker replayer", "Pete Gordon", "BSD-3-Clause", "https://github.com/pete-gordon/hivelytracker", "hvl"),
        playback("v2m-player", "farbrausch, Joakim L. Gilje", "Artistic-2.0", "https://github.com/jgilje/v2m-player", "v2m"),
        playback("z80ex", "Boo-boo", "GPL-2.0", "https://sourceforge.net/projects/z80ex", "z80ex"),
        codec("FLAC", "Xiph.Org Foundation", "BSD-3-Clause", "https://xiph.org/flac", "FLAC"),
        codec("Ogg", "Xiph.Org Foundation", "BSD-3-Clause", "https://xiph.org/ogg", "ogg"),
        codec("Vorbis", "Xiph.Org Foundation", "BSD-3-Clause", "https://xiph.org/vorbis", "vorbis"),
        codec("Opus", "Xiph.Org, Skype, Octasic et al.", "BSD-3-Clause", "https://opus-codec.org", "opus"),
        codec("mpg123", "Michael Hipp, Thomas Orgis et al.", "LGPL-2.1", "https://mpg123.org", "mpg123"),
        codec("minimp3", "lieff", "CC0-1.0", "https://github.com/lieff/minimp3", "minimp3"),
        codec("FFmpeg", "FFmpeg developers", "LGPL-2.1+", "https://ffmpeg.org", "ffmpeg"),
        codec("LibAtrac9", "Alex Barney", "MIT", "https://github.com/Thealexbarney/LibAtrac9", "atrac9"),
        system("zlib", "Jean-loup Gailly, Mark Adler", "Zlib", "https://zlib.net", "zlib"),
        system("LZMA SDK", "Igor Pavlov", "Public domain", "https://www.7-zip.org/sdk.html", "lzma"),
        system("lhasa", "Simon Howard", "ISC", "https://github.com/fragglet/lhasa", "lhasa"),
        system("UnRAR", "Alexander Roshal", "UnRAR license", "https://www.rarlab.com", "unrar"),
        system("{fmt}", "Victor Zverovich", "MIT", "https://fmt.dev", "fmt"),
        system("libadrenotools", "Billy Laws", "BSD-2-Clause", "https://github.com/bylaws/libadrenotools", "adrenotools"),
        system("liblinkernsbypass", "Billy Laws", "BSD-2-Clause", "https://github.com/bylaws/liblinkernsbypass"),
        android("AndroidX libraries", "The Android Open Source Project", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
        android("Kotlin, kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://kotlinlang.org"),
        android("FlexboxLayout", "Google", "Apache-2.0", "https://github.com/google/flexbox-layout"),
        android("jsoup", "Jonathan Hedley", "MIT", "https://jsoup.org"),
        android("ANR-WatchDog", "Salomon Brys", "MIT", "https://github.com/SalomonBrys/ANR-WatchDog"),
        android("JSR-305 annotations", "FindBugs", "Apache-2.0", "https://github.com/findbugsproject/findbugs"),
        reference("corrscope (trigger algorithm)", "jimbo1qaz", "BSD-2-Clause", "https://github.com/corrscope/corrscope"),
        reference("Furnace (per-chip voices)", "tildearrow et al.", "GPL-2.0+", "https://github.com/tildearrow/furnace"),
        reference("JSIDPlay2 (SID filter presets)", "Ken Händel et al.", "GPL", "https://sourceforge.net/projects/jsidplay2"),
        reference("AYEmul", "S.Bulba", "Freeware", "https://bulba.untergrund.net/emulator_e.htm"),
    )

    // 3rdparty/ entries not shipped with application
    val NOT_SHIPPED = setOf("curl", "lame", "runtime")
}
