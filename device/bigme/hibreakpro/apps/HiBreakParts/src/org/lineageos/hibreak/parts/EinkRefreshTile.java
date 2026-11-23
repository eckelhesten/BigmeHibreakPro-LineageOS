/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.StatusBarManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Closes the panel, then does one full refresh of the screen underneath. */
public class EinkRefreshTile extends TileService {
    private static final long PANEL_CLOSE_DELAY_MS = 400;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = () -> {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return;
        Eink.fullRefresh();
    };

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onClick() {
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        if (sbm != null) sbm.collapsePanels();
        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, PANEL_CLOSE_DELAY_MS);
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mRefresh);
        super.onDestroy();
    }
}
