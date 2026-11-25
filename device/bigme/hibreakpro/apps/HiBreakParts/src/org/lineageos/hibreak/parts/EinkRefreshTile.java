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
    // The process's, not the service's: SystemUI may unbind the tile once the panel has closed,
    // and the refresh must still run then (the app is persistent).
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static PowerManager sPowerManager;
    private static final Runnable sRefresh = () -> {
        if (sPowerManager != null && !sPowerManager.isInteractive()) return;
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
        if (sPowerManager == null) {
            sPowerManager = getApplicationContext().getSystemService(PowerManager.class);
        }
        sHandler.removeCallbacks(sRefresh);
        sHandler.postDelayed(sRefresh, PANEL_CLOSE_DELAY_MS);
    }
}
