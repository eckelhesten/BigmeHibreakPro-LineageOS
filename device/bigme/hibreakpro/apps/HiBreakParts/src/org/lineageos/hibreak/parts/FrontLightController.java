/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.android.settingslib.display.BrightnessUtils;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Mirrors the default display's brightness onto the LM3630A front light.
 *
 * - The light follows the brightness Android actually applies (slider, dim, screen off).
 * - The light is off whenever the phone is asleep. The sleep screen (always-on display) keeps
 *   the display state ON so the panel takes the clock's updates, and Android's BrightnessInfo
 *   keeps reporting the user's brightness there, so "asleep" comes from PowerManager.
 * - The light follows the slider position (perceptual), not the linear brightness value.
 * - Slider at its minimum turns the light off, as e-ink readers usually want.
 * - Auto brightness works the other way round from a phone with a backlit screen: a reflective
 *   e-ink panel is lit by the room, so it needs the front light most in the dark and not at all
 *   in daylight. The slider sets the level for an ordinary room (same curve as in manual mode,
 *   so it gives a visible range everywhere) and the light sensor scales it by a gain: up to
 *   1.6x in the dark, less in brighter light, off in daylight. Android's own auto curve is flat
 *   (FrameworksResHiBreakPro), so the slider position it applies in auto mode is the user's
 *   adjustment. The gain curve can be tuned at runtime without a rebuild:
 *   debug.hibreak.frontlight.auto_lux and .auto_gain, comma-separated, same length.
 * - The light reacts within about a second when the room gets darker (the panel becomes hard
 *   to read at once), and more slowly when it gets brighter, so a passing shadow or a lamp
 *   switched on doesn't make it jump.
 * - Settings.System "hibreak_frontlight_warmth" (0 = all cold .. 100 = all warm, default 70)
 *   splits the level between the two channels; "hibreak_frontlight_enabled" = 0 keeps it off.
 * - Runs on its own thread: the LM3630A writes are blocking I2C sysfs writes (two every 40 ms
 *   while auto mode ramps), and they kept the app's main thread (the e-ink policy, the tiles)
 *   waiting.
 */
class FrontLightController implements DisplayManager.DisplayListener, SensorEventListener {
    private static final String TAG = "HiBreakFrontLight";
    private static final String DIR = "/sys/devices/platform/11d01000.i2c7/i2c-7/7-0036/";
    private static final String COLD = DIR + "lm3630a_cold_light";
    private static final String WARM = DIR + "lm3630a_warm_light";
    private static final int MAX = 255;

    /**
     * Auto mode: ambient light (lux) to a gain on the slider's level, in between on a log scale.
     * Inverse: the darker the room, the more light. The MTK sensor reads high (~3500-5000 lux
     * under ordinary ceiling lights in the evening, ~0 covered), so the curve is set by its
     * readings, not by real lux: about 0.75 under room lights, 1.6 in the dark, off in daylight.
     */
    private static final float[] AUTO_LUX = {0f, 10f, 100f, 1000f, 5000f, 20000f, 60000f};
    private static final float[] AUTO_GAIN = {1.6f, 1.5f, 1.25f, 1f, 0.7f, 0.3f, 0f};
    private static final String PROP_AUTO_LUX = "debug.hibreak.frontlight.auto_lux";
    private static final String PROP_AUTO_GAIN = "debug.hibreak.frontlight.auto_gain";
    /**
     * Time constants of the light sensor smoothing: quick when the room gets darker (more light
     * needed now), slower when it gets brighter, so a passing shadow doesn't change the light.
     */
    private static final long LUX_DARKER_MS = 1000;
    private static final long LUX_BRIGHTER_MS = 2500;
    /**
     * The sensor reports on change only: a covered sensor sends one reading and then nothing. The
     * smoothing keeps moving towards the last reading on this tick until it gets there.
     */
    private static final long LUX_TICK_MS = 200;
    /** Auto mode changes the light in small steps instead of jumping. */
    private static final long RAMP_STEP_MS = 40;

    private final Context mContext;
    private final Handler mHandler;
    private DisplayManager mDisplayManager;
    private PowerManager mPowerManager;
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private boolean mSensorRegistered;
    /**
     * Smoothed ambient light, or < 0 before the first reading. Kept across screen-off, so auto
     * mode has a value at once when the screen comes on (the sensor reports on change only).
     */
    private float mLux = -1f;
    private long mLuxTime;
    /** The sensor's last reading, which the smoothed value moves towards. */
    private float mRawLux = -1f;
    /** The first reading since the sensor was registered is taken as it is, without smoothing. */
    private boolean mFreshSensor;
    private int mLoggedLevel = -1;
    private int mLastCold = -1;
    private int mLastWarm = -1;
    /** Last perceptual slider position (0..1); changes to it bypass auto-mode ramp/deadband. */
    private float mLastSlider = -1f;
    /** Level the light is ramping to in auto mode, and the level it is at. */
    private int mTarget = -1;
    private int mLevel = -1;
    private boolean mWasOn;
    private boolean mWasAuto;

    private final Runnable mRampStep = this::rampStep;
    private final Runnable mLuxTick = this::luxTick;

    /** The tuning properties' last text and what it parsed to (autoGain runs on every tick). */
    private static String sLuxText;
    private static String sGainText;
    private static float[] sLux = AUTO_LUX;
    private static float[] sGain = AUTO_GAIN;

    FrontLightController(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_FOREGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    void start() {
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mPowerManager = mContext.getSystemService(PowerManager.class);
        // Asleep/awake: the display events alone don't tell, see the class comment.
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                update();
            }
        }, filter, null, mHandler, Context.RECEIVER_NOT_EXPORTED);
        mSensorManager = mContext.getSystemService(SensorManager.class);
        if (mSensorManager != null) {
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
        mDisplayManager.registerDisplayListener(this, mHandler,
                DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                        | DisplayManager.EVENT_TYPE_DISPLAY_STATE
                        | DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS);
        ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Keys.FRONTLIGHT_WARMTH), false, observer);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Keys.FRONTLIGHT_ENABLED), false, observer);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false,
                observer);
        mHandler.post(this::update);
    }

    @Override
    public void onDisplayAdded(int displayId) {}

    @Override
    public void onDisplayRemoved(int displayId) {}

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) update();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length == 0
                || !Float.isFinite(event.values[0])) {
            return;
        }
        float newLux = Math.max(0f, event.values[0]);
        long now = SystemClock.uptimeMillis();
        if (mLux < 0f || mFreshSensor) {
            // First reading after the screen came on: use it as it is.
            mFreshSensor = false;
            mRawLux = newLux;
            mLux = newLux;
            mLuxTime = now;
        } else {
            // Advance towards the previous reading up to now before adopting the new target,
            // so a long quiet spell doesn't make (now - mLuxTime) >= tc and snap instantly.
            smoothLux(now);
            mRawLux = newLux;
        }
        update();
        scheduleLuxTick();
    }

    /**
     * Moves the smoothed value towards the last reading for the time since the last step, on a
     * log scale like the gain curve, so 3000 -> 0 lux takes as long as 30 -> 0.
     */
    private void smoothLux(long now) {
        long dt = Math.max(0L, now - mLuxTime);
        mLuxTime = now;
        if (dt == 0L || mRawLux < 0f) return;
        if (Math.abs(Math.log1p(mRawLux) - Math.log1p(mLux)) <= 0.02) {
            mLux = mRawLux;
            return;
        }
        long tc = mRawLux < mLux ? LUX_DARKER_MS : LUX_BRIGHTER_MS;
        double k = 1.0 - Math.exp(-dt / (double) tc);
        double cur = Math.log1p(mLux);
        cur += (Math.log1p(mRawLux) - cur) * k;
        mLux = (float) Math.expm1(cur);
        if (Math.abs(Math.log1p(mRawLux) - Math.log1p(mLux)) <= 0.02) {
            mLux = mRawLux;
        }
    }

    private void scheduleLuxTick() {
        mHandler.removeCallbacks(mLuxTick);
        // Until within ~2 % of the reading (on the log scale); a new reading reschedules.
        if (mSensorRegistered && Math.abs(Math.log1p(mRawLux) - Math.log1p(mLux)) > 0.02) {
            mHandler.postDelayed(mLuxTick, LUX_TICK_MS);
        }
    }

    private void luxTick() {
        if (!mSensorRegistered || mRawLux < 0f) return;
        smoothLux(SystemClock.uptimeMillis());
        update();
        scheduleLuxTick();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    static int getWarmth(Context context) {
        int w = Settings.System.getInt(SystemSettingsStore.deviceResolver(context),
                Keys.FRONTLIGHT_WARMTH,
                Keys.DEFAULT_WARMTH);
        return Math.max(0, Math.min(100, w));
    }

    private boolean isAutoMode() {
        return mLightSensor != null && Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    private void setSensorEnabled(boolean enabled) {
        if (enabled == mSensorRegistered) return;
        if (enabled) {
            mFreshSensor = true;
            mSensorRegistered = mSensorManager.registerListener(this, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else {
            mSensorManager.unregisterListener(this);
            mSensorRegistered = false;
            mHandler.removeCallbacks(mLuxTick);
        }
    }

    private void update() {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        boolean enabled = Settings.System.getInt(mContext.getContentResolver(),
                Keys.FRONTLIGHT_ENABLED, 1) != 0;
        boolean on = enabled && display != null && display.getState() == Display.STATE_ON
                && mPowerManager.isInteractive();
        boolean auto = isAutoMode();
        if (mWasAuto && !auto) {
            // Out of auto mode: the last reading may be hours old when auto comes back, and the
            // sensor reports on change only. Until its first reading, auto is as manual.
            mLux = -1f;
            mRawLux = -1f;
        }
        mWasAuto = auto;
        setSensorEnabled(on && auto);

        int level = 0;
        boolean sliderChanged = false;
        if (on) {
            BrightnessInfo info = display.getBrightnessInfo();
            if (info == null || !Float.isFinite(info.brightness)
                    || !Float.isFinite(info.brightnessMinimum)
                    || !Float.isFinite(info.brightnessMaximum)
                    || info.brightnessMaximum <= info.brightnessMinimum
                    || info.brightness < 0f) {
                // No brightness to go by for a moment (the display changing state): the light
                // stays as it is instead of going off and on again.
                return;
            }
            // Slider position (perceptual, 0..1), not the linear brightness: the linear value
            // stays tiny over most of the slider, and the LM3630A's output is roughly linear
            // in its code, invisible below ~64 in a lit room (measured on camera).
            float pos = BrightnessUtils.convertLinearToGammaFloat(info.brightness,
                    info.brightnessMinimum, info.brightnessMaximum)
                    / (float) BrightnessUtils.GAMMA_SPACE_MAX;
            pos = Float.isFinite(pos) ? Math.max(0f, Math.min(1f, pos)) : 0f;
            sliderChanged = mLastSlider >= 0f && Math.abs(pos - mLastSlider) > 0.002f;
            mLastSlider = pos;
            if (pos > 0.01f) {
                // Bottom of the slider = off; then 1..MAX on a gentle curve, so the lower
                // half still has the dim levels needed for reading in the dark.
                float slider = 1 + (MAX - 1) * (float) Math.pow(pos, 1.5);
                // Auto: scaled by the ambient light; no reading yet = as in manual mode.
                float gain = auto && mLux >= 0f ? autoGain(mLux) : 1f;
                level = Math.round(slider * gain);
                level = Math.min(MAX, gain > 0f ? Math.max(1, level) : 0);
            }
            if (level != mLoggedLevel) {
                mLoggedLevel = level;
                Log.i(TAG, "level " + level + " (slider " + Math.round(pos * 100) + "%"
                        + (auto ? ", auto, lux " + Math.round(mLux) : "") + ")");
            }
        }

        boolean wasOn = mWasOn;
        mWasOn = on;
        if (on && auto && wasOn && mLevel >= 0 && !sliderChanged) {
            // Auto mode: move there in steps. Small ambient-light changes wait, so the light
            // doesn't wander, but never block turning on from 0 or turning off to 0.
            // The current level is applied again in case the warmth changed.
            if (level > 0 && mTarget > 0 && Math.abs(level - mTarget) < 3) {
                apply(mLevel);
                return;
            }
            mTarget = level;
            mHandler.removeCallbacks(mRampStep);
            if (mLevel == mTarget) {
                apply(mLevel); // already there: the warmth may have changed
            } else {
                rampStep();
            }
        } else {
            mHandler.removeCallbacks(mRampStep);
            mTarget = level;
            apply(level);
        }
    }

    private void rampStep() {
        if (mLevel == mTarget) return;
        int diff = mTarget - mLevel;
        int step = Integer.signum(diff) * Math.max(1, Math.abs(diff) / 6);
        apply(mLevel + step);
        if (mLevel != mTarget) mHandler.postDelayed(mRampStep, RAMP_STEP_MS);
    }

    /** Gain on the slider's level for the ambient light, interpolated on a log scale of the lux. */
    static synchronized float autoGain(float lux) {
        // Parsed again only when the tuning text changed.
        String luxText = SystemProperties.get(PROP_AUTO_LUX);
        String gainText = SystemProperties.get(PROP_AUTO_GAIN);
        if (!luxText.equals(sLuxText) || !gainText.equals(sGainText)) {
            sLuxText = luxText;
            sGainText = gainText;
            sLux = parseList(luxText, AUTO_LUX, true);
            sGain = parseList(gainText, AUTO_GAIN, false);
        }
        float[] xs = sLux;
        float[] ys = sGain;
        if (xs.length != ys.length) {
            xs = AUTO_LUX;
            ys = AUTO_GAIN;
        }
        if (lux <= xs[0]) return Math.max(0f, ys[0]);
        for (int i = 1; i < xs.length; i++) {
            if (lux < xs[i]) {
                double a = Math.log1p(xs[i - 1]);
                double b = Math.log1p(xs[i]);
                float t = b > a ? (float) ((Math.log1p(lux) - a) / (b - a)) : 1f;
                return Math.max(0f, ys[i - 1] + (ys[i] - ys[i - 1]) * t);
            }
        }
        return Math.max(0f, ys[ys.length - 1]);
    }

    /** Comma-separated finite floats (strictly increasing non-negative for xs), or def. */
    private static float[] parseList(String text, float[] def, boolean monotonicNonNegative) {
        if (text == null || text.isEmpty()) return def;
        String[] parts = text.split(",");
        if (parts.length == 0) return def;
        float[] out = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                float v = Float.parseFloat(parts[i].trim());
                if (!Float.isFinite(v)) return def;
                if (monotonicNonNegative && (v < 0f || (i > 0 && v <= out[i - 1]))) return def;
                out[i] = v;
            }
        } catch (NumberFormatException e) {
            return def;
        }
        return out;
    }

    private void apply(int level) {
        mLevel = level;
        int warmth = getWarmth(mContext);
        // Both channels at full level in the middle, one channel fading out towards either end.
        int cold = Math.round(level * Math.min(1f, (100 - warmth) / 50f));
        int warm = Math.round(level * Math.min(1f, warmth / 50f));
        if (cold != mLastCold && write(COLD, cold)) {
            mLastCold = cold;
        }
        if (warm != mLastWarm && write(WARM, warm)) {
            mLastWarm = warm;
        }
    }

    private static boolean write(String path, int value) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(Integer.toString(value));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "write " + path + " failed", e);
            return false;
        }
    }
}
