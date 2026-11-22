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

/** Cycles the global e-ink refresh mode: Default, Clear (GC16), Balanced (GL16), Fast (A2). */
public class EinkModeTile extends TileService {
    static final int[] MODE_LABELS = {
            R.string.mode_default, R.string.mode_clear, R.string.mode_balanced, R.string.mode_fast};

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
                    Settings.System.getUriFor(Keys.EINK_MODE), false, mObserver);
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
        int next = (Eink.getGlobalMode(getContentResolver()) + 1) % Eink.MODE_VALUES.length;
        Eink.setGlobalMode(getContentResolver(), next);
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        int i = Eink.getGlobalMode(getContentResolver());
        tile.setState(i == 0 ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.setSubtitle(getString(MODE_LABELS[i]));
        tile.updateTile();
    }
}
