/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.SparseArray;

import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches the sleep screen's weather: hourly while it is on (in Doze, at the system's
 * maintenance windows), and once at once after a settings change or when the phone goes to
 * sleep with old data. Only with a network; failures are retried with the scheduler's backoff.
 */
public class WeatherJobService extends JobService {
    private static final int JOB_PERIODIC = 0x48420001;
    private static final int JOB_NOW = 0x48420002;
    private static final long HOUR_MS = 60 * 60 * 1000L;
    /** The hourly job skips its run when a fetch (at sleep, after a change) is newer than this. */
    private static final long FRESH_MS = 25 * 60 * 1000L;
    private final Object mLock = new Object();
    private final SparseArray<ActiveJob> mActive = new SparseArray<>();

    private static final class ActiveJob {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<HttpURLConnection> conn = new AtomicReference<>();
        volatile Thread thread;

        void cancel() {
            cancelled.set(true);
            HttpURLConnection c = conn.getAndSet(null);
            if (c != null) {
                // Not on the main thread (onStopJob): closing a TLS socket writes to it.
                new Thread(() -> {
                    try {
                        c.disconnect();
                    } catch (RuntimeException ignored) {
                        // Best-effort socket abort.
                    }
                }, "HiBreakWeatherCancel").start();
            }
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }

    /** Starts or stops the hourly job after a settings change; {@code now} fetches at once. */
    static void schedule(Context context, boolean now) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName service = new ComponentName(context, WeatherJobService.class);
        if (!Weather.enabled(context.getContentResolver())) {
            js.cancel(JOB_PERIODIC);
            js.cancel(JOB_NOW);
            Weather.clear(context.getContentResolver());
            return;
        }
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(HOUR_MS, HOUR_MS / 3)
                    .build());
        }
        if (now) {
            js.schedule(new JobInfo.Builder(JOB_NOW, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build());
        }
    }

    /** A fetch at once (going to sleep with old data), unless one is already waiting. */
    static void scheduleNowIfIdle(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js != null && js.getPendingJob(JOB_NOW) != null) return;
        schedule(context, true);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int id = params.getJobId();
        if (id == JOB_PERIODIC && Weather.enabled(getContentResolver())) {
            long age = System.currentTimeMillis() - Weather.fetchedAt(getContentResolver());
            if (age >= 0 && age < FRESH_MS) return false;  // fetched recently: nothing to do
        }
        ActiveJob job = new ActiveJob();
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                if (!job.cancelled.get()) ok = Weather.update(this, job.cancelled, job.conn);
            } finally {
                synchronized (mLock) {
                    ActiveJob cur = mActive.get(id);
                    if (cur == job) mActive.remove(id);
                }
                if (!job.cancelled.get()) jobFinished(params, !ok);
            }
        }, "HiBreakWeather");
        job.thread = t;
        synchronized (mLock) {
            ActiveJob prev = mActive.get(id);
            if (prev != null) prev.cancel();
            mActive.put(id, job);
        }
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        synchronized (mLock) {
            ActiveJob job = mActive.get(params.getJobId());
            if (job != null) {
                job.cancel();
                mActive.remove(params.getJobId());
            }
        }
        return Weather.enabled(getContentResolver());
    }
}
