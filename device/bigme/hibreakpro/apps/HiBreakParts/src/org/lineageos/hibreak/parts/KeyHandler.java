/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.DeviceKeyHandler;

/**
 * Side keys of the built-in keypad (mtk-kpd sends KEY_F1 / KEY_F2). Loaded into system_server
 * by PhoneWindowManager through LineageOS's config_deviceKeyHandlerLibs/Classes (overlay
 * LineageSdkHiBreakPro). Runs without the app's resources.
 *
 * Actions (Settings.System hibreak_key_f1 / hibreak_key_f2): none, eink_refresh, eink_mode,
 * frontlight, flashlight, screenshot, camera, notifications, app:<package>.
 */
public class KeyHandler implements DeviceKeyHandler {
    private static final String TAG = "HiBreakKeyHandler";
    private static final int SCAN_F1 = 59;
    private static final int SCAN_F2 = 60;
    private static final String KEYPAD_NAME = "mtk-kpd";
    private static final int DEVICE_UNKNOWN = Integer.MIN_VALUE;
    private static final int MAX_TORCH_RETRIES = 6;

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private volatile int mKeypadDeviceId = DEVICE_UNKNOWN;
    private volatile boolean mTorchOn;
    private String mTorchCameraId;
    private boolean mTorchCallbackRegistered;
    private int mTorchRetries;
    private boolean mTorchRetryScheduled;
    private final Runnable mRetryInitTorch = () -> {
        mTorchRetryScheduled = false;
        initTorch();
    };

    public KeyHandler(Context context) {
        mContext = context;
        mHandler.post(() -> {
            resolveKeypad();
            initTorch();
        });
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        final String setting;
        if (event.getScanCode() == SCAN_F1 && event.getKeyCode() == KeyEvent.KEYCODE_F1) {
            setting = Keys.KEY_F1;
        } else if (event.getScanCode() == SCAN_F2 && event.getKeyCode() == KeyEvent.KEYCODE_F2) {
            setting = Keys.KEY_F2;
        } else {
            return event;
        }
        // Only the built-in keypad, not F1/F2 of an external keyboard. The device id is looked
        // up off the input thread; until then, accept.
        int keypad = mKeypadDeviceId;
        if (keypad == DEVICE_UNKNOWN) {
            mHandler.post(this::resolveKeypad);
        } else if (event.getDeviceId() != keypad) {
            return event;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            mHandler.post(() -> run(setting));
        }
        return null;
    }

    private void resolveKeypad() {
        if (mKeypadDeviceId != DEVICE_UNKNOWN) return;
        try {
            InputManager im = mContext.getSystemService(InputManager.class);
            if (im == null) return;
            for (int id : im.getInputDeviceIds()) {
                InputDevice dev = im.getInputDevice(id);
                if (dev != null && KEYPAD_NAME.equals(dev.getName())) {
                    mKeypadDeviceId = id;
                    return;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "keypad lookup failed", e);
        }
    }

    private Context userContext(int userId) {
        if (userId == UserHandle.USER_SYSTEM) return mContext;
        try {
            return mContext.createContextAsUser(UserHandle.of(userId), 0);
        } catch (RuntimeException e) {
            return mContext;
        }
    }

    private void run(String setting) {
        int userId = ActivityManager.getCurrentUser();
        ContentResolver cr = mContext.getContentResolver();
        String action = Settings.System.getStringForUser(cr, setting, userId);
        if (action == null) {
            action = Keys.KEY_F1.equals(setting) ? Keys.DEFAULT_KEY_F1 : Keys.DEFAULT_KEY_F2;
        }
        try {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            boolean interactive = pm == null || pm.isInteractive();
            if (action.startsWith("app:")) {
                launchApp(action.substring(4), userId);
                return;
            }
            switch (action) {
                case "eink_refresh":
                    if (interactive) {
                        Settings.System.putStringForUser(cr, Keys.EINK_REFRESH_REQUEST,
                                Long.toString(SystemClock.elapsedRealtimeNanos()),
                                UserHandle.USER_SYSTEM);
                    }
                    break;
                case "eink_mode":
                    if (interactive) {
                        // Device-wide, like the refresh request: EinkPolicy and FrontLightController
                        // run in user 0 and only observe user 0's settings.
                        // Default <-> Reading.
                        int profile = Eink.clampProfile(Settings.System.getIntForUser(cr,
                                Keys.EINK_PROFILE, Eink.PROFILE_DEFAULT, UserHandle.USER_SYSTEM));
                        Settings.System.putIntForUser(cr, Keys.EINK_PROFILE,
                                profile == Eink.PROFILE_READING
                                        ? Eink.PROFILE_DEFAULT : Eink.PROFILE_READING,
                                UserHandle.USER_SYSTEM);
                    }
                    break;
                case "frontlight":
                    if (interactive) {
                        int on = Settings.System.getIntForUser(cr, Keys.FRONTLIGHT_ENABLED, 1,
                                UserHandle.USER_SYSTEM);
                        Settings.System.putIntForUser(cr, Keys.FRONTLIGHT_ENABLED,
                                on != 0 ? 0 : 1, UserHandle.USER_SYSTEM);
                    }
                    break;
                case "flashlight":
                    toggleTorch();
                    break;
                case "screenshot":
                    if (interactive) injectKey(KeyEvent.KEYCODE_SYSRQ);
                    break;
                case "camera":
                    wakeUp();
                    KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
                    boolean locked = km != null && km.isKeyguardLocked();
                    startActivity(new Intent(locked
                            ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                            : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), userId);
                    break;
                case "notifications":
                    wakeUp();
                    StatusBarManager sbm = mContext.getSystemService(StatusBarManager.class);
                    if (sbm != null) sbm.expandNotificationsPanel();
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "action " + action + " failed", e);
        }
    }

    private void launchApp(String pkg, int userId) {
        Intent intent = userContext(userId).getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return;
        wakeUp();
        startActivity(intent, userId);
    }

    private void startActivity(Intent intent, int userId) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mContext.startActivityAsUser(intent, UserHandle.of(userId));
    }

    private void wakeUp() {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                    "hibreak:sidekey");
        }
    }

    private void injectKey(int keyCode) {
        InputManager im = mContext.getSystemService(InputManager.class);
        if (im == null) return;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent ev = new KeyEvent(now, now, action, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            im.injectInputEvent(ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
        }
    }

    private void initTorch() {
        if (mTorchCallbackRegistered) return;
        CameraManager cm = mContext.getSystemService(CameraManager.class);
        if (cm == null) return;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    mTorchCameraId = id;
                    cm.registerTorchCallback(new CameraManager.TorchCallback() {
                        @Override
                        public void onTorchModeChanged(String cameraId, boolean enabled) {
                            if (cameraId.equals(mTorchCameraId)) mTorchOn = enabled;
                        }

                        @Override
                        public void onTorchModeUnavailable(String cameraId) {
                            if (cameraId.equals(mTorchCameraId)) mTorchOn = false;
                        }
                    }, mHandler);
                    mTorchCallbackRegistered = true;
                    mTorchRetryScheduled = false;
                    mHandler.removeCallbacks(mRetryInitTorch);
                    break;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "torch init failed", e);
        }
        if (!mTorchCallbackRegistered && !mTorchRetryScheduled
                && mTorchRetries < MAX_TORCH_RETRIES) {
            mTorchRetryScheduled = true;
            mTorchRetries++;
            mHandler.postDelayed(mRetryInitTorch, 2000L * mTorchRetries);
        }
    }

    private void toggleTorch() {
        CameraManager cm = mContext.getSystemService(CameraManager.class);
        if (cm == null) return;
        try {
            if (!mTorchCallbackRegistered) initTorch();
            if (mTorchCameraId != null) {
                boolean next = !mTorchOn;
                cm.setTorchMode(mTorchCameraId, next);
                mTorchOn = next;
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "torch toggle failed", e);
        }
    }
}
