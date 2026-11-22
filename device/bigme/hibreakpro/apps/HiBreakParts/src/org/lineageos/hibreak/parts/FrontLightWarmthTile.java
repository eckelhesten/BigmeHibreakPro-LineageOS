/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.AlertDialog;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Opens a warmth slider in the quick settings panel. Long-press opens the settings page. */
public class FrontLightWarmthTile extends TileService {
    /** While dragging, the setting (and with it both light channels) is written this often. */
    private static final long WRITE_INTERVAL_MS = 80;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mDragging;
    private boolean mListening;
    private AlertDialog mDialog;
    private int mPending = -1;
    private long mLastWrite;
    private final Runnable mWritePending = this::writePending;
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (!mDragging) refresh();
        }
    };

    @Override
    public void onStartListening() {
        if (!mListening) {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Keys.FRONTLIGHT_WARMTH), false, mObserver);
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Keys.FRONTLIGHT_ENABLED), false, mObserver);
            mListening = true;
        }
        refresh();
    }

    @Override
    public void onStopListening() {
        unregisterObserver();
    }

    private void unregisterObserver() {
        if (mListening) {
            getContentResolver().unregisterContentObserver(mObserver);
            mListening = false;
        }
    }

    @Override
    public void onClick() {
        int current = FrontLightController.getWarmth(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        TextView label = new TextView(this);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setText(getString(R.string.warmth_value, current));
        box.addView(label);

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(current);
        bar.setPadding(0, pad / 2, 0, pad / 2);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                label.setText(getString(R.string.warmth_value, progress));
                mPending = progress;
                long wait = mLastWrite + WRITE_INTERVAL_MS - SystemClock.uptimeMillis();
                mHandler.removeCallbacks(mWritePending);
                if (wait <= 0) {
                    writePending();
                } else {
                    mHandler.postDelayed(mWritePending, wait);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mHandler.removeCallbacks(mWritePending);
                writePending(); // the value where the finger let go
                mDragging = false;
                refresh();
            }
        });
        box.addView(bar);

        AlertDialog dialog = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.tile_warmth)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> {
            mHandler.removeCallbacks(mWritePending);
            writePending();
            mDragging = false;
            if (mDialog == dialog) mDialog = null;
            refresh();
        });
        if (mDialog != null) mDialog.dismiss();
        mDialog = dialog;
        showDialog(dialog);
    }

    private void writePending() {
        if (mPending < 0) return;
        Settings.System.putInt(getContentResolver(), Keys.FRONTLIGHT_WARMTH, mPending);
        mPending = -1;
        mLastWrite = SystemClock.uptimeMillis();
    }

    @Override
    public void onDestroy() {
        unregisterObserver();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        mHandler.removeCallbacks(mWritePending);
        writePending();
        mDragging = false;
        super.onDestroy();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = Settings.System.getInt(getContentResolver(),
                Keys.FRONTLIGHT_ENABLED, 1) != 0;
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(R.string.warmth_value, FrontLightController.getWarmth(this)));
        tile.updateTile();
    }
}
