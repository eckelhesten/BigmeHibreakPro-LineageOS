/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.Application;
import android.os.UserHandle;

/** Persistent system-uid process: front light, e-ink policy, the sleep screen's corners. */
public class PartsApp extends Application {
    private FrontLightController mFrontLight;
    private EinkPolicy mEinkPolicy;
    private SleepScreenInfo mSleepScreenInfo;

    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        Defaults.applyOnce(this);
        mFrontLight = new FrontLightController(this);
        mFrontLight.start();
        mEinkPolicy = new EinkPolicy(this);
        mEinkPolicy.start();
        mSleepScreenInfo = new SleepScreenInfo(this);
        mSleepScreenInfo.start();
    }
}
