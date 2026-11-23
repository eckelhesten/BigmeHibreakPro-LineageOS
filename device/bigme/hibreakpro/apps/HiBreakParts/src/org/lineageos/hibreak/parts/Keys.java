/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

/**
 * Settings.System names shared by the app process and KeyHandler (system_server).
 * No resource references here: KeyHandler is loaded without the app's resources.
 * Preference keys in res/xml/settings.xml are these names without PREFIX.
 */
final class Keys {
    static final String PREFIX = "hibreak_";

    static final String FRONTLIGHT_ENABLED = PREFIX + "frontlight_enabled"; // 1/0, default 1
    static final String FRONTLIGHT_WARMTH = PREFIX + "frontlight_warmth";   // 0..100, default 70
    // E-ink profile: 0 Default (fast, everyday), 1 Reading (clean refreshes). Default 0.
    static final String EINK_PROFILE = PREFIX + "eink_profile";
    // Per-app profiles "pkg=idx;..." with idx 0 Default, 1 Reading, 2 Default with colours
    // inverted (dark-only apps). Apps without an entry: Reading for known e-readers
    // (Eink.READING_APPS), otherwise the global profile.
    static final String EINK_APP_PROFILES = PREFIX + "eink_app_profiles";
    // Bench only (scripts/eink-bench): a raw composer mode index (0 Normal, 1 Clean, 2 HD,
    // 3 Fast) that overrides every profile while set; delete it to go back to the profiles.
    static final String EINK_BENCH_MODE = PREFIX + "eink_bench_mode";
    // Greys in Default (A2), per temperature band: "maxC:l0,...,l15:gamma_x100;..." (bands in
    // rising order, the first whose maxC is at or above the battery temperature applies).
    // Written by the bench (scripts/eink-bench/greys.py) after calibrating; empty = identity,
    // unset = DEFAULT_FAST_MAPS.
    static final String EINK_FAST_MAPS = PREFIX + "eink_fast_maps";
    // Before the profiles (Defaults version 10 migrates them): the mode index (0 Default/DU,
    // 1 Clear, 2 Balanced, 3 Fast) and per-app modes (same, 4 = inverted).
    static final String LEGACY_EINK_MODE = PREFIX + "eink_mode";
    static final String LEGACY_EINK_APP_MODES = PREFIX + "eink_app_modes";
    // Written by the shell or the bench (any new value): one full refresh now.
    static final String EINK_REFRESH_REQUEST = PREFIX + "eink_refresh";
    static final String KEY_F1 = PREFIX + "key_f1";                         // action, see KeyHandler
    static final String KEY_F2 = PREFIX + "key_f2";

    // Sleep screen corners (SleepScreenInfo; drawn by SystemUI's EinkSleepScreen).
    static final String SLEEP_WEATHER = PREFIX + "sleep_weather";           // 1/0, default 0
    static final String SLEEP_WEATHER_UNIT = PREFIX + "sleep_weather_unit"; // c/f, default: regional preferences
    static final String SLEEP_WEATHER_CITY = PREFIX + "sleep_weather_city"; // "lat;lon;name"
    static final String SLEEP_CALENDAR = PREFIX + "sleep_calendar";         // 1/0, default 1
    // Written for SystemUI, read at every sleep screen redraw. Empty = nothing to show.
    // "2;kind;day 1/0;temperature in C;unit c/f;fetched at ms;WMO code;condition;city"
    static final String SLEEP_WEATHER_DATA = PREFIX + "sleep_weather_data";
    // "1;begin ms;end ms;all day 1/0;title" (all-day events in local midnights)
    static final String SLEEP_CALENDAR_DATA = PREFIX + "sleep_calendar_data";

    static final int DEFAULT_WARMTH = 70;
    // GR III-calibrated A2 level map and gamma 1.35 (g1/a2_16 + corpus bench: keeps light UI
    // surfaces like WhatsApp's #EFEAE2 chat background and cards clearly separated from white,
    // without crushing dark tones), for every temperature until a warm band is measured.
    static final String DEFAULT_FAST_MAPS = "100:0,2,3,5,6,6,7,8,9,9,10,10,11,11,12,15:135";
    static final String DEFAULT_KEY_F1 = "eink_refresh";
    static final String DEFAULT_KEY_F2 = "eink_mode";

    private Keys() {}
}
