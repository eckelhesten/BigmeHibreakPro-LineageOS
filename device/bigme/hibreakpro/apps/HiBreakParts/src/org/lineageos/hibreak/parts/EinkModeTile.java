/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Reading on or off: switches the global e-ink profile between Default and Reading. */
public class EinkModeTile extends TileService {

    private boolean mListening;
    private final ContentObserver mObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            refresh();
        }
    };

    @Override
    public void onStartListening() {
        if (!mListening) {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Keys.EINK_PROFILE), false, mObserver);
            mListening = true;
        }
        refresh();
    }

    @Override
    public void onStopListening() {
        unregisterObserver();
    }

    @Override
    public void onDestroy() {
        unregisterObserver();
        super.onDestroy();
    }

    private void unregisterObserver() {
        if (mListening) {
            getContentResolver().unregisterContentObserver(mObserver);
            mListening = false;
        }
    }

    @Override
    public void onClick() {
        Eink.setProfile(getContentResolver(),
                Eink.getProfile(getContentResolver()) == Eink.PROFILE_READING
                        ? Eink.PROFILE_DEFAULT : Eink.PROFILE_READING);
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean reading = Eink.getProfile(getContentResolver()) == Eink.PROFILE_READING;
        tile.setState(reading ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(null);
        tile.updateTile();
    }
}
