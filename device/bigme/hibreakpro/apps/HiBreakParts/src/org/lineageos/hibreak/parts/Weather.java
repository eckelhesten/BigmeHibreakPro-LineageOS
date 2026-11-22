/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.net.TrafficStats;
import android.provider.Settings;
import android.util.Log;

import androidx.core.text.util.LocalePreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Weather for the sleep screen from Open-Meteo (open-meteo.com: free, no account, no key).
 * What leaves the phone: the place name typed into the city search, and the chosen city's
 * coordinates about once an hour. Blocking calls; never on the main thread.
 */
final class Weather {
    private static final String TAG = "HiBreakWeather";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int TRAFFIC_TAG = 0x48420001;

    static final class City {
        final String name;
        final String detail;
        final double lat;
        final double lon;

        City(String name, String detail, double lat, double lon) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
        }

        String label() {
            return detail.isEmpty() ? name : name + ", " + detail;
        }

        String encode() {
            return lat + ";" + lon + ";" + name.replace(';', ',');
        }

        /** From the setting ("lat;lon;name"), or null. */
        static City decode(String s) {
            if (s == null) return null;
            String[] f = s.split(";", 3);
            if (f.length != 3 || f[2].isEmpty()) return null;
            try {
                double lat = Double.parseDouble(f[0]);
                double lon = Double.parseDouble(f[1]);
                if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0
                        || !Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
                    return null;
                }
                return new City(f[2], "", lat, lon);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private Weather() {}

    /**
     * Settings > System > Languages > Regional preferences > Temperature, or what the region of
     * the phone's language uses (en-US: Fahrenheit). The unit setting overrides it.
     */
    static String defaultUnit() {
        return LocalePreferences.TemperatureUnit.FAHRENHEIT.equals(
                LocalePreferences.getTemperatureUnit()) ? "f" : "c";
    }

    static String unit(ContentResolver cr) {
        String u = Settings.System.getString(cr, Keys.SLEEP_WEATHER_UNIT);
        return "f".equals(u) || "c".equals(u) ? u : defaultUnit();
    }

    static boolean enabled(ContentResolver cr) {
        return Settings.System.getInt(cr, Keys.SLEEP_WEATHER, 0) != 0
                && City.decode(Settings.System.getString(cr, Keys.SLEEP_WEATHER_CITY)) != null;
    }

    private static String optText(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key);
    }

    /** Places matching a name, best first (Open-Meteo geocoding). */
    static List<City> search(String query) throws IOException, JSONException {
        String lang = Locale.getDefault().getLanguage();
        JSONObject json = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count=8&format=json"
                + "&language=" + URLEncoder.encode(lang, StandardCharsets.UTF_8), null, null));
        List<City> cities = new ArrayList<>();
        JSONArray results = json.optJSONArray("results");
        if (results == null) return cities;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String name = optText(r, "name");
            if (name.isEmpty()) continue;
            double lat = r.optDouble("latitude", Double.NaN);
            double lon = r.optDouble("longitude", Double.NaN);
            if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0
                    || !Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
                continue;
            }
            StringBuilder detail = new StringBuilder();
            for (String key : new String[] {"admin1", "country"}) {
                String part = optText(r, key);
                if (part.isEmpty() || part.equals(name)) continue;
                if (detail.length() > 0) detail.append(", ");
                detail.append(part);
            }
            cities.add(new City(name, detail.toString(), lat, lon));
        }
        return cities;
    }

    static synchronized void clear(ContentResolver cr) {
        Settings.System.putString(cr, Keys.SLEEP_WEATHER_DATA, "");
    }

    /**
     * Fetches the current weather for the chosen city and stores it for the sleep screen, or
     * clears it when the weather is off. Returns false when it should be retried.
     */
    static boolean update(Context context, AtomicBoolean cancelled,
            AtomicReference<HttpURLConnection> connRef) {
        ContentResolver cr = context.getContentResolver();
        String rawCity = Settings.System.getString(cr, Keys.SLEEP_WEATHER_CITY);
        City city = City.decode(rawCity);
        if (Settings.System.getInt(cr, Keys.SLEEP_WEATHER, 0) == 0 || city == null) {
            clear(cr);
            return true;
        }
        try {
            JSONObject current = new JSONObject(get(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto",
                    city.lat, city.lon), cancelled, connRef)).getJSONObject("current");
            double celsius = current.getDouble("temperature_2m");
            if (!Double.isFinite(celsius)) throw new JSONException("non-finite temperature");
            int code = current.getInt("weather_code");
            boolean day = current.optInt("is_day", 1) != 0;
            String data = String.format(Locale.ROOT,
                    "2;%s;%d;%.1f;%s;%d;%d;%s;%s", kind(code), day ? 1 : 0, celsius, unit(cr),
                    System.currentTimeMillis(), code, condition(context, code), city.name);
            // The switch, city, or job cancellation may have changed while waiting for the network.
            synchronized (Weather.class) {
                if ((cancelled != null && cancelled.get()) || !enabled(cr)
                        || !rawCity.equals(Settings.System.getString(cr, Keys.SLEEP_WEATHER_CITY))) {
                    return true;
                }
                Settings.System.putString(cr, Keys.SLEEP_WEATHER_DATA, data);
            }
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "weather update failed: " + e);
            return false;
        }
    }

    /**
     * The unit or the language changed: rewrite the stored weather's unit and condition
     * (no network).
     */
    static synchronized void relabel(Context context) {
        ContentResolver cr = context.getContentResolver();
        String data = Settings.System.getString(cr, Keys.SLEEP_WEATHER_DATA);
        if (data == null) return;
        String[] f = data.split(";", 9);
        if (f.length != 9 || !"2".equals(f[0])) return;
        f[4] = unit(cr);
        try {
            f[7] = condition(context, Integer.parseInt(f[6]));
        } catch (NumberFormatException e) {
            return;
        }
        Settings.System.putString(cr, Keys.SLEEP_WEATHER_DATA, String.join(";", f));
    }

    /** A word or two for the weather, in the phone's language. */
    static String condition(Context context, int code) {
        final int res;
        if (code == 0 || code == 1) {
            res = R.string.weather_clear;
        } else if (code == 2) {
            res = R.string.weather_partly_cloudy;
        } else if (code == 3) {
            res = R.string.weather_cloudy;
        } else if (code == 45 || code == 48) {
            res = R.string.weather_fog;
        } else if (code >= 51 && code <= 57) {
            res = R.string.weather_drizzle;
        } else if (code >= 61 && code <= 67) {
            res = R.string.weather_rain;
        } else if (code >= 80 && code <= 82) {
            res = R.string.weather_showers;
        } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            res = R.string.weather_snow;
        } else if (code >= 95) {
            res = R.string.weather_thunderstorm;
        } else {
            res = R.string.weather_cloudy;
        }
        // No field separators in the stored line.
        return context.getString(res).replace(';', ',');
    }

    /** WMO weather code (Open-Meteo weather_code) to the sleep screen's icon kinds. */
    static String kind(int code) {
        if (code == 0 || code == 1) return "clear";
        if (code == 2) return "partly";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 95) return "thunder";
        return "cloudy";
    }

    private static String get(String url, AtomicBoolean cancelled,
            AtomicReference<HttpURLConnection> connRef) throws IOException {
        TrafficStats.setThreadStatsTag(TRAFFIC_TAG);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (connRef != null) {
                connRef.set(c);
                if (cancelled != null && cancelled.get()) {
                    throw new IOException("cancelled");
                }
            }
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "HiBreakParts");
            int status = c.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(buf)) != -1; ) {
                    total += n;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new IOException("response too large");
                    }
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } finally {
            if (connRef != null) connRef.compareAndSet(c, null);
            if (c != null) c.disconnect();
            TrafficStats.clearThreadStatsTag();
        }
    }
}
