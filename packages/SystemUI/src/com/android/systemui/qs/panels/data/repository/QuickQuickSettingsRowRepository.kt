/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.data.repository

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.kotlin.mapDirect
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@SysUISingleton
class QuickQuickSettingsRowRepository @Inject constructor(
    @Application private val context: Context,
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware private val configurationRepository: ConfigurationRepository,
    secureSettingsRepository: SecureSettingsRepository,
) {
    private fun settingsChanges(): Flow<Unit> = callbackFlow {
        val uris: List<Uri> = listOf(
            Settings.System.getUriFor(Settings.System.QQS_TILES_ROWS),
            Settings.System.getUriFor(Settings.System.QQS_TILES_ROWS_LANDSCAPE)
        )
        val observer = object : ContentObserver(/* handler */ null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val cr = context.contentResolver
        uris.forEach { cr.registerContentObserver(it, /* notifyForDescendants */ false, observer, UserHandle.USER_ALL) }
        awaitClose { cr.unregisterContentObserver(observer) }
    }

    private fun readRows(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) Settings.System.QQS_TILES_ROWS_LANDSCAPE
                  else Settings.System.QQS_TILES_ROWS
        val def = resources.getInteger(R.integer.quick_qs_paginated_grid_num_rows)
        return Settings.System.getIntForUser(
            context.contentResolver, key, def, UserHandle.USER_CURRENT
        ).coerceAtLeast(1)
    }

    /** User-configured rows (Settings.System QQS_TILES_ROWS[_LANDSCAPE], EvoX 12.3). */
    private val configRows: Flow<Int> =
        merge(configurationRepository.onConfigurationChange, settingsChanges())
            .emitOnStart()
            .mapDirect { readRows() }
            .distinctUntilChanged()

    // NX809J: RedMagic Control sets this while an external display is connected, so the
    // touchpad / display-off tiles it moves into the list get their own row in the collapsed
    // shade instead of pushing the user's first tiles out (0-2 extra rows).
    /** Extra rows requested by RedMagic Control (0 when no external display). */
    val extraRows: Flow<Int> =
        secureSettingsRepository.intSetting(EXTRA_ROWS_SETTING, 0).map { it.coerceIn(0, 2) }

    val rows: Flow<Int> = combine(configRows, extraRows) { base, extra -> base + extra }

    /** Rows the collapsed shade shows before the RedMagic Control pinned row. */
    val baseRows: Flow<Int> = configRows

    val defaultRows: Int =
        resources.getInteger(R.integer.quick_qs_paginated_grid_num_rows)

    private companion object {
        const val EXTRA_ROWS_SETTING = "rm_qqs_extra_rows"
    }
}
