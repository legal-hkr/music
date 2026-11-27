package sh.hnet.music.equalizer

import sh.hnet.music.playback.EqFilter
import sh.hnet.music.playback.EqSettings
import sh.hnet.music.playback.FilterType

/** A made-up headphone correction of the usual shape – shelves, cuts and a boost, its pre-amp against clipping – for the tests. */
internal object TestPresets {
    val CORRECTION = EqSettings(
        preampDb = -6f,
        filters = listOf(
            EqFilter(type = FilterType.LOW_SHELF, frequencyHz = 105, gainDb = 6f, q = 0.70f),
            EqFilter(type = FilterType.PEAK, frequencyHz = 1500, gainDb = -7.5f, q = 2.4f),
            EqFilter(type = FilterType.PEAK, frequencyHz = 3000, gainDb = 6f, q = 0.65f),
            EqFilter(type = FilterType.PEAK, frequencyHz = 962, gainDb = -6f, q = 1.06f),
            EqFilter(type = FilterType.PEAK, frequencyHz = 320, gainDb = -1.5f, q = 0.52f),
            EqFilter(type = FilterType.HIGH_SHELF, frequencyHz = 10000, gainDb = -3.5f, q = 0.70f),
        ),
    )
}
