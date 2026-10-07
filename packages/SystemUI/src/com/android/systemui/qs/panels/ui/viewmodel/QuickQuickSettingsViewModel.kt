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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class QuickQuickSettingsViewModel
@AssistedInject
constructor(
    tilesInteractor: CurrentTilesInteractor,
    qsColumnsViewModelFactory: QSColumnsViewModel.Factory,
    quickQuickSettingsRowInteractor: QuickQuickSettingsRowInteractor,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    val squishinessViewModel: TileSquishinessViewModel,
    iconTilesViewModel: IconTilesViewModel,
    val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
) : HydratedActivatable() {

    private val qsColumnsViewModel = qsColumnsViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)
    private val mediaInRowViewModel =
        mediaInRowInLandscapeViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)

    val columns: Int
        get() = qsColumnsViewModel.columns

    private val largeTiles by iconTilesViewModel.largeTiles.hydratedStateOf()

    private val rows: Int
        get() =
            if (mediaInRowViewModel.shouldMediaShowInRow) {
                rowsWithoutMedia * 2
            } else {
                rowsWithoutMedia
            }

    private val rowsWithoutMedia by
        quickQuickSettingsRowInteractor.rows.hydratedStateOf(
            initialValue = quickQuickSettingsRowInteractor.defaultRows
        )

    private val largeTilesSpan: Int
        get() = qsColumnsViewModel.largeSpan

    private val currentTiles by tilesInteractor.currentTiles.hydratedStateOf()

    private val baseRows by
        quickQuickSettingsRowInteractor.baseRows.hydratedStateOf(
            initialValue = quickQuickSettingsRowInteractor.defaultRows
        )

    private val extraRows by quickQuickSettingsRowInteractor.extraRows.hydratedStateOf(0)

    val tileViewModels by derivedStateOf {
        val sized =
            currentTiles.map {
                SizedTileImpl(TileViewModel(it.tile, it.spec, it.expandable), it.spec.width())
            }
        // NX809J: while an external display is connected RedMagic Control asks for an extra row
        // and the touchpad / display-off tiles fill it, right under the user's first rows. Done
        // here, for the collapsed view only: SystemUI owns sysui_qs_tiles and reverts outside
        // edits, and the user's own tile order must not change anyway.
        val pinned =
            if (extraRows > 0) sized.filter { it.tile.spec.isExtDisplayTile() } else emptyList()
        if (pinned.isEmpty()) {
            splitInRowsSequence(sized, columns).take(rows).toList().flatten()
        } else {
            // Pinned tiles start the first extra row; whatever space they leave is filled with
            // the user's next tiles (e.g. a wide Audio output tile) instead of staying empty.
            val rest = sized.filterNot { it in pinned }
            val mediaFactor = if (rows > rowsWithoutMedia) 2 else 1
            val head =
                splitInRowsSequence(rest, columns).take(baseRows * mediaFactor).toList().flatten()
            val ordered = head + pinned + rest.drop(head.size)
            splitInRowsSequence(ordered, columns).take(rows).toList().flatten()
        }
    }

    override suspend fun onActivated() {
        coroutineScope {
            launch { qsColumnsViewModel.activate() }
            launch { mediaInRowViewModel.activate() }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickQuickSettingsViewModel
    }

    private fun TileSpec.width(): Int = if (largeTiles.contains(this)) largeTilesSpan else 1

    companion object {
        /** RedMagic Control's touchpad and display-off tiles, matched by component. */
        private fun TileSpec.isExtDisplayTile(): Boolean {
            val cn = (this as? TileSpec.CustomTileSpec)?.componentName ?: return false
            return cn.packageName == "com.nubia.rmcontrol" &&
                (cn.className.endsWith(".TouchpadTile") || cn.className.endsWith(".MonitorOnlyTile"))
        }

        /** Behavior of the media carousel in quick quick settings */
        @VisibleForTesting
        val mediaUiBehavior: MediaUiBehavior
            get() =
                MediaUiBehavior(
                    isCarouselDismissible = true,
                    carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                )
    }
}
