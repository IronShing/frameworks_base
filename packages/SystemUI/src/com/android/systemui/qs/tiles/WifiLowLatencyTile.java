/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2026 The EvolutionX Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SettingObserver;

import javax.inject.Inject;

/**
 * Quick settings tile: WiFi low latency.
 *
 * Toggles the {@code wifi_low_latency_forced} Settings.Global key. When on, the Wi-Fi framework
 * forces low-latency mode (chip power-save off) whenever Wi-Fi is connected, which removes the
 * power-save micro-sleep ping jitter that hurts online gaming. See WifiLockManager.
 */
public class WifiLowLatencyTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "wifi_low_latency";

    /** Keep in sync with WifiLockManager.WIFI_LOW_LATENCY_FORCED. */
    private static final String WIFI_LOW_LATENCY_FORCED = "wifi_low_latency_forced";

    @Nullable
    private Icon mIcon = null;

    private static final Intent WIFI_SETTINGS =
            new Intent(Settings.ACTION_WIFI_SETTINGS);

    private final SettingObserver mSetting;

    @Inject
    public WifiLowLatencyTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            GlobalSettings globalSettings,
            UserTracker userTracker
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mSetting = new SettingObserver(globalSettings, mHandler,
                WIFI_LOW_LATENCY_FORCED, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        setEnabled(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return WIFI_SETTINGS;
    }

    private void setEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                WIFI_LOW_LATENCY_FORCED,
                enabled ? 1 : 0);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean enabled = value != 0;
        state.value = enabled;
        state.label = mContext.getString(R.string.quick_settings_wifi_low_latency_label);
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_wifi_low_latency);
        }
        state.icon = mIcon;
        if (enabled) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_wifi_low_latency_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_wifi_low_latency_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_wifi_low_latency_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }
}
