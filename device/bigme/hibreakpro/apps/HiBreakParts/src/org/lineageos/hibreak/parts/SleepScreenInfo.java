/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Instances;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Keeps the sleep screen's corners current (Settings > Display > E-ink & front light >
 * Sleep screen). SystemUI draws them from two settings (see Keys); this class writes them:
 *
 * - Weather: WeatherJobService fetches it; started and stopped from here when the settings change,
 *   and asked for a fresh value when the phone goes to sleep with data older than 30 minutes.
 * - Next event: the next entry from Android's calendar provider, so every calendar app that
 *   stores its events there (local calendars, DAVx5, Etar, Google Calendar sync, ...) counts.
 *   Visible calendars only; cancelled and declined events skipped, 7 days ahead. The first
 *   event by day: an all-day event wins when it starts on an earlier day than the next timed
 *   event (tomorrow's birthday before Saturday's appointment); on the same day the timed event
 *   wins, so a birthday today doesn't hide today's 14:00 meeting. Recomputed when the
 *   calendars change, when the phone goes to sleep, when the time or zone changes, and when the
 *   shown event ends (a non-waking alarm: it fires at the next minute tick after the end).
 */
class SleepScreenInfo {
    private static final String TAG = "HiBreakSleepInfo";
    private static final long MINUTE = 60 * 1000L;
    private static final long DAY = 24 * 60 * MINUTE;
    private static final long LOOKAHEAD = 7 * DAY;
    private static final long WEATHER_REFRESH_AT_SLEEP = 30 * MINUTE;
    private static final int MAX_TITLE_LEN = 120;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private boolean mCalendarObserverRegistered;
    private final Runnable mUpdateCalendar = this::updateCalendar;
    private final AlarmManager.OnAlarmListener mEventEnded = this::updateCalendarSoon;

    SleepScreenInfo(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        HandlerThread thread = new HandlerThread("HiBreakSleepInfo");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    void start() {
        mResolver.registerContentObserver(Settings.System.getUriFor(Keys.SLEEP_WEATHER), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        WeatherJobService.schedule(mContext, true);
                    }
                });
        mResolver.registerContentObserver(Settings.System.getUriFor(Keys.SLEEP_WEATHER_CITY),
                false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        // Old city's weather off the screen until the new one is in.
                        Weather.clear(mResolver);
                        WeatherJobService.schedule(mContext, true);
                    }
                });
        mResolver.registerContentObserver(Settings.System.getUriFor(Keys.SLEEP_WEATHER_UNIT),
                false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        Weather.relabel(mContext);
                    }
                });
        mResolver.registerContentObserver(Settings.System.getUriFor(Keys.SLEEP_CALENDAR), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateCalendarSoon();
                    }
                });

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    registerCalendarObserver();
                }
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // Run immediately before the sleep screen draws and the CPU suspends.
                    mHandler.removeCallbacks(mUpdateCalendar);
                    updateCalendar();
                    refreshWeatherIfOld();
                } else {
                    updateCalendarSoon();
                }
                // The condition's language and the default unit follow the locale.
                if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                    Weather.relabel(mContext);
                }
            }
        }, filter, null, mHandler, Context.RECEIVER_NOT_EXPORTED);

        mHandler.post(() -> {
            registerCalendarObserver();
            refreshWeatherIfOld();
            WeatherJobService.schedule(mContext, false);
            updateCalendar();
        });
    }

    private void registerCalendarObserver() {
        if (mCalendarObserverRegistered) return;
        try {
            mResolver.registerContentObserver(CalendarContract.CONTENT_URI, true,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            updateCalendarSoon();
                        }
                    });
            mCalendarObserverRegistered = true;
        } catch (RuntimeException e) {
            // No calendar provider (yet) or CE storage still locked: retried on USER_UNLOCKED.
            Log.w(TAG, "calendar observer: " + e);
        }
    }

    private void refreshWeatherIfOld() {
        if (!Weather.enabled(mResolver)) return;
        String data = Settings.System.getString(mResolver, Keys.SLEEP_WEATHER_DATA);
        long fetched = 0;
        if (data != null) {
            String[] f = data.split(";", 9);
            if (f.length >= 7) {
                try {
                    fetched = Long.parseLong(f[5]);  // the same field in versions 1 and 2
                } catch (NumberFormatException ignored) {
                    // refetch
                }
            }
        }
        long age = System.currentTimeMillis() - fetched;
        if (fetched <= 0 || age < 0 || age > WEATHER_REFRESH_AT_SLEEP) {
            WeatherJobService.schedule(mContext, true);
        }
    }

    private void updateCalendarSoon() {
        // Calendar sync writes in bursts; one query after it settles.
        mHandler.removeCallbacks(mUpdateCalendar);
        mHandler.postDelayed(mUpdateCalendar, 1000);
    }

    private void updateCalendar() {
        AlarmManager alarms = mContext.getSystemService(AlarmManager.class);
        alarms.cancel(mEventEnded);
        if (Settings.System.getInt(mResolver, Keys.SLEEP_CALENDAR, 1) == 0) {
            put("");
            return;
        }
        final long now = System.currentTimeMillis();
        final long today = startDay(now, now);
        Event timed = null;
        Event ongoingMultiDay = null;
        Event allDay = null;
        Uri.Builder uri = Instances.CONTENT_URI.buildUpon();
        // All-day instances are stored in UTC days: look a day further each way.
        ContentUris.appendId(uri, now - DAY);
        ContentUris.appendId(uri, now + LOOKAHEAD + DAY);
        String[] projection = {Instances.TITLE, Instances.BEGIN, Instances.END,
                Instances.ALL_DAY, Instances.SELF_ATTENDEE_STATUS, Instances.STATUS};
        try (Cursor c = mResolver.query(uri.build(), projection, Instances.VISIBLE + "=1", null,
                Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(4) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                if (!c.isNull(5) && c.getInt(5) == CalendarContract.Events.STATUS_CANCELED) {
                    continue;
                }
                final boolean isAllDay = c.getInt(3) != 0;
                long begin = c.getLong(1);
                long end = c.getLong(2);
                if (isAllDay) {
                    begin = localMidnight(begin);
                    end = localMidnight(end);
                }
                if (end <= now || begin > now + LOOKAHEAD) continue;
                Event e = new Event(c.getString(0), begin, end, isAllDay);
                if (isAllDay) {
                    if (allDay == null || begin < allDay.begin) allDay = e;
                } else if (begin < today) {
                    // Ongoing multi-day timed event (e.g. on-call): keep as fallback so it
                    // doesn't hide today's actual meetings for the rest of the week.
                    if (ongoingMultiDay == null || end < ongoingMultiDay.end) ongoingMultiDay = e;
                } else if (timed == null || begin < timed.begin) {
                    timed = e;
                }
            }
        } catch (RuntimeException e) {
            // Locked storage before the first unlock, or no calendar provider.
            Log.w(TAG, "calendar query: " + e);
            return;
        }
        if (timed == null) timed = ongoingMultiDay;
        // By day: the earlier start day wins, the timed event on the same day. Events that have
        // already started count from today.
        Event next = timed;
        if (allDay != null && (timed == null
                || startDay(allDay.begin, now) < startDay(timed.begin, now))) {
            next = allDay;
        }
        if (next == null) {
            put("");
            return;
        }
        String title = next.title == null || next.title.isBlank()
                ? mContext.getString(R.string.sleep_calendar_no_title)
                : next.title.replaceAll("\\s+", " ").trim();
        if (title.length() > MAX_TITLE_LEN) {
            int cut = MAX_TITLE_LEN;
            if (Character.isHighSurrogate(title.charAt(cut - 1))) cut--;
            title = title.substring(0, cut);
        }
        put("1;" + next.begin + ";" + next.end + ";" + (next.allDay ? 1 : 0) + ";" + title);
        // Show the next one once this one is over; not worth waking the phone for.
        alarms.set(AlarmManager.RTC, next.end, "HiBreakSleepInfo", mEventEnded, mHandler);
    }

    private void put(String value) {
        if (!value.equals(Settings.System.getString(mResolver, Keys.SLEEP_CALENDAR_DATA))) {
            Settings.System.putString(mResolver, Keys.SLEEP_CALENDAR_DATA, value);
        }
    }

    /** Local midnight of the day an event starts on, or of today if it has already started. */
    private static long startDay(long begin, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Math.max(begin, now));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** An all-day instance's time (midnight UTC of its date) as local midnight of that date. */
    private static long localMidnight(long utc) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(utc);
        Calendar local = Calendar.getInstance();
        local.clear();
        local.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH));
        return local.getTimeInMillis();
    }

    private static final class Event {
        final String title;
        final long begin;
        final long end;
        final boolean allDay;

        Event(String title, long begin, long end, boolean allDay) {
            this.title = title;
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
        }
    }
}
