/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.permission.access.appop

import android.app.AppOpsManager
import android.companion.virtual.VirtualDeviceManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.permission.PermissionManager
import android.provider.Settings
import android.util.ArrayMap
import android.util.ArraySet
import android.util.LongSparseArray
import android.util.Slog
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.util.IntPair
import com.android.server.LocalServices
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.appop.AppOpsCheckingServiceInterface.AppOpsModeChangedListener
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.DevicePermissionUri
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.PackageUri
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.appop.AppOpModes.MODE_ALLOWED
import com.android.server.permission.access.appop.AppOpModes.MODE_FOREGROUND
import com.android.server.permission.access.appop.AppOpModes.MODE_IGNORED
import com.android.server.permission.access.collection.forEachIndexed
import com.android.server.permission.access.collection.set
import com.android.server.permission.access.immutable.anyIndexed
import com.android.server.permission.access.permission.AppIdPermissionPolicy
import com.android.server.permission.access.permission.DevicePermissionPolicy
import com.android.server.permission.access.permission.PermissionFlags
import com.android.server.permission.access.permission.PermissionService
import com.android.server.pm.UserManagerInternal

class AppOpService(private val service: AccessCheckingService) : AppOpsCheckingServiceInterface {
    private val packagePolicy =
        service.getSchemePolicy(PackageUri.SCHEME, AppOpUri.SCHEME) as PackageAppOpPolicy
    private val appIdPolicy =
        service.getSchemePolicy(UidUri.SCHEME, AppOpUri.SCHEME) as AppIdAppOpPolicy
    private val permissionPolicy =
        service.getSchemePolicy(UidUri.SCHEME, PermissionUri.SCHEME) as AppIdPermissionPolicy
    private val devicePermissionPolicy =
        service.getSchemePolicy(UidUri.SCHEME, DevicePermissionUri.SCHEME) as DevicePermissionPolicy

    private val context = service.context

    // Maps appop code to its runtime permission
    private val runtimeAppOpToPermissionNames = SparseArray<String>()

    // Maps runtime permission to its appop codes
    private val runtimePermissionNameToAppOp = ArrayMap<String, Int>()

    private var foregroundableOps = SparseBooleanArray()

    /* Maps foreground permissions to their background permission. Background permissions aren't
    required to be runtime */
    private val foregroundToBackgroundPermissionName = ArrayMap<String, String>()

    /* Maps background permissions to their foreground permissions. Background permissions aren't
    required to be runtime */
    private val backgroundToForegroundPermissionNames = ArrayMap<String, ArraySet<String>>()

    private lateinit var handler: Handler

    // RedMagic privacy guard: Settings.Secure rm_privacy_guard = "pkg:mask,pkg:mask" (per user;
    // mask bits 1 = camera, 2 = microphone, 4 = location). A guarded package's runtime app op is
    // reported as MODE_IGNORED while its permission stays granted, so the app never notices the
    // block. Runtime-permission op modes are derived from permission flags right here (setUidMode
    // is a no-op for them), so this is the only place such an override can live.
    // userId -> packageName -> mask
    @Volatile private var privacyGuard = SparseArray<ArrayMap<String, Int>>()
    private val privacyGuardLock = Any()

    @Volatile private var listeners = ArraySet<AppOpsModeChangedListener>()
    private val listenersLock = Any()

    fun initialize() {
        // TODO(b/252883039): Wrong handler. Inject main thread handler here.
        handler = Handler(context.mainLooper)

        appIdPolicy.addOnAppOpModeChangedListener(OnAppIdAppOpModeChangedListener())
        packagePolicy.addOnAppOpModeChangedListener(OnPackageAppOpModeChangedListener())
    }

    @VisibleForTesting
    override fun writeState() {
        // Not implemented because writes are handled automatically.
    }

    override fun readState() {
        // Not implemented because reads are handled automatically.
    }

    @VisibleForTesting
    override fun shutdown() {
        // Not implemented because writes are handled automatically.
    }

    override fun systemReady() {
        createPermissionAppOpMapping()
        val permissionListener = OnPermissionFlagsChangedListener()
        permissionPolicy.addOnPermissionFlagsChangedListener(permissionListener)
        devicePermissionPolicy.addOnPermissionFlagsChangedListener(permissionListener)
        registerPrivacyGuardObserver()
    }

    private fun privacyGuardBitForOp(appOpCode: Int): Int =
        when (appOpCode) {
            AppOpsManager.OP_CAMERA -> PRIVACY_GUARD_CAMERA
            AppOpsManager.OP_RECORD_AUDIO -> PRIVACY_GUARD_MIC
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION -> PRIVACY_GUARD_LOCATION
            AppOpsManager.OP_READ_CONTACTS,
            AppOpsManager.OP_WRITE_CONTACTS -> PRIVACY_GUARD_CONTACTS
            AppOpsManager.OP_READ_MEDIA_IMAGES,
            AppOpsManager.OP_READ_MEDIA_VIDEO,
            AppOpsManager.OP_READ_MEDIA_VISUAL_USER_SELECTED -> PRIVACY_GUARD_MEDIA_VISUAL
            AppOpsManager.OP_READ_MEDIA_AUDIO -> PRIVACY_GUARD_MEDIA_AUDIO
            AppOpsManager.OP_READ_EXTERNAL_STORAGE,
            AppOpsManager.OP_WRITE_EXTERNAL_STORAGE -> PRIVACY_GUARD_FILES
            AppOpsManager.OP_BLUETOOTH_SCAN,
            AppOpsManager.OP_BLUETOOTH_CONNECT,
            AppOpsManager.OP_BLUETOOTH_ADVERTISE -> PRIVACY_GUARD_NEARBY
            AppOpsManager.OP_READ_PHONE_STATE,
            AppOpsManager.OP_READ_PHONE_NUMBERS,
            AppOpsManager.OP_READ_CALL_LOG,
            AppOpsManager.OP_WRITE_CALL_LOG -> PRIVACY_GUARD_PHONE
            AppOpsManager.OP_READ_SMS,
            AppOpsManager.OP_RECEIVE_SMS,
            AppOpsManager.OP_SEND_SMS,
            AppOpsManager.OP_RECEIVE_MMS -> PRIVACY_GUARD_SMS
            AppOpsManager.OP_READ_CALENDAR,
            AppOpsManager.OP_WRITE_CALENDAR -> PRIVACY_GUARD_CALENDAR
            AppOpsManager.OP_BODY_SENSORS,
            AppOpsManager.OP_ACTIVITY_RECOGNITION -> PRIVACY_GUARD_SENSORS
            else -> 0
        }

    private fun readPrivacyGuard(userId: Int): ArrayMap<String, Int> {
        val out = ArrayMap<String, Int>()
        val raw =
            try {
                Settings.Secure.getStringForUser(
                    context.contentResolver,
                    PRIVACY_GUARD_SETTING,
                    userId,
                )
            } catch (e: Exception) {
                null
            }
        if (raw.isNullOrEmpty()) {
            return out
        }
        for (item in raw.split(',')) {
            val colon = item.lastIndexOf(':')
            if (colon <= 0) {
                continue
            }
            val mask = item.substring(colon + 1).trim().toIntOrNull() ?: continue
            if (mask != 0) {
                out[item.substring(0, colon).trim()] = mask
            }
        }
        return out
    }

    private fun allUserIds(): IntArray =
        LocalServices.getService(UserManagerInternal::class.java)?.userIds
            ?: intArrayOf(UserHandle.USER_SYSTEM)

    private fun registerPrivacyGuardObserver() {
        synchronized(privacyGuardLock) {
            val guard = SparseArray<ArrayMap<String, Int>>()
            for (userId in allUserIds()) {
                guard.put(userId, readPrivacyGuard(userId))
            }
            privacyGuard = guard
        }
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?, userId: Int) {
                    if (userId == UserHandle.USER_ALL) {
                        for (u in allUserIds()) {
                            reloadPrivacyGuard(u)
                        }
                    } else {
                        reloadPrivacyGuard(userId)
                    }
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(PRIVACY_GUARD_SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
    }

    private fun reloadPrivacyGuard(userId: Int) {
        val newRules = readPrivacyGuard(userId)
        val oldRules: ArrayMap<String, Int>
        synchronized(privacyGuardLock) {
            oldRules = privacyGuard[userId] ?: ArrayMap()
            if (oldRules == newRules) {
                return
            }
            val guard = SparseArray<ArrayMap<String, Int>>()
            privacyGuard.forEachIndexed { _, u, rules -> guard.put(u, rules) }
            guard.put(userId, newRules)
            privacyGuard = guard
        }
        Slog.i(LOG_TAG, "privacy guard changed for user $userId: $newRules")
        // Tell AppOpsService (cache invalidation, op-changed watchers such as audioserver and
        // cameraserver) about every (uid, op) whose effective mode may have changed.
        val listenersLocal = listeners
        val ops =
            intArrayOf(
                AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION,
                AppOpsManager.OP_READ_CONTACTS,
                AppOpsManager.OP_WRITE_CONTACTS,
                AppOpsManager.OP_READ_MEDIA_IMAGES,
                AppOpsManager.OP_READ_MEDIA_VIDEO,
                AppOpsManager.OP_READ_MEDIA_VISUAL_USER_SELECTED,
                AppOpsManager.OP_READ_MEDIA_AUDIO,
                AppOpsManager.OP_READ_EXTERNAL_STORAGE,
                AppOpsManager.OP_WRITE_EXTERNAL_STORAGE,
                AppOpsManager.OP_BLUETOOTH_SCAN,
                AppOpsManager.OP_BLUETOOTH_CONNECT,
                AppOpsManager.OP_BLUETOOTH_ADVERTISE,
                AppOpsManager.OP_READ_PHONE_STATE,
                AppOpsManager.OP_READ_PHONE_NUMBERS,
                AppOpsManager.OP_READ_CALL_LOG,
                AppOpsManager.OP_WRITE_CALL_LOG,
                AppOpsManager.OP_READ_SMS,
                AppOpsManager.OP_RECEIVE_SMS,
                AppOpsManager.OP_SEND_SMS,
                AppOpsManager.OP_RECEIVE_MMS,
                AppOpsManager.OP_READ_CALENDAR,
                AppOpsManager.OP_WRITE_CALENDAR,
                AppOpsManager.OP_BODY_SENSORS,
                AppOpsManager.OP_ACTIVITY_RECOGNITION,
            )
        val packageNames = ArraySet<String>(oldRules.keys)
        packageNames.addAll(newRules.keys)
        packageNames.forEachIndexed { _, packageName ->
            val changedBits = (oldRules[packageName] ?: 0) xor (newRules[packageName] ?: 0)
            if (changedBits == 0) {
                return@forEachIndexed
            }
            val appId =
                service.getState { state.externalState.packageStates[packageName]?.appId }
                    ?: return@forEachIndexed
            val uid = UserHandle.getUid(userId, appId)
            for (op in ops) {
                if ((changedBits and privacyGuardBitForOp(op)) == 0) {
                    continue
                }
                val mode = getUidMode(uid, VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT, op)
                listenersLocal.forEachIndexed { _, listener ->
                    listener.onUidModeChanged(
                        uid,
                        op,
                        mode,
                        VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                    )
                }
            }
        }
    }

    /** Any guarded package in the app ID blocks the whole (shared) UID. */
    private fun GetStateScope.isPrivacyGuarded(appId: Int, userId: Int, op: Int): Boolean {
        val bit = privacyGuardBitForOp(op)
        if (bit == 0) {
            return false
        }
        val rules = privacyGuard[userId] ?: return false
        if (rules.isEmpty()) {
            return false
        }
        val packageNames = state.externalState.appIdPackageNames[appId] ?: return false
        return packageNames.anyIndexed { _, packageName -> ((rules[packageName] ?: 0) and bit) != 0 }
    }

    private fun createPermissionAppOpMapping() {
        val permissions = service.getState { with(permissionPolicy) { getPermissions() } }

        for (appOpCode in 0 until AppOpsManager._NUM_OP) {
            // Ops that default to MODE_FOREGROUND are foregroundable.
            if (AppOpsManager.opToDefaultMode(appOpCode) == AppOpsManager.MODE_FOREGROUND) {
                foregroundableOps[appOpCode] = true
            }
            AppOpsManager.opToPermission(appOpCode)?.let { permissionName ->
                // Multiple ops might map to a single permission but only one is considered the
                // runtime appop calculations.
                if (appOpCode == AppOpsManager.permissionToOpCode(permissionName)) {
                    val permission =
                        checkNotNull(permissions[permissionName]) {
                            "Missing permission definition for permission \"$permissionName\"" +
                                " associated with app op $appOpCode"
                        }
                    if (permission.isRuntime) {
                        runtimePermissionNameToAppOp[permissionName] = appOpCode
                        runtimeAppOpToPermissionNames[appOpCode] = permissionName
                        permission.permissionInfo.backgroundPermission?.let {
                            backgroundPermissionName ->
                            // Note: background permission may not be runtime,
                            // e.g. microphone/camera.
                            foregroundableOps[appOpCode] = true
                            foregroundToBackgroundPermissionName[permissionName] =
                                backgroundPermissionName
                            backgroundToForegroundPermissionNames
                                .getOrPut(backgroundPermissionName, ::ArraySet)
                                .add(permissionName)
                        }
                    }
                }
            }
        }
    }

    override fun getNonDefaultUidModes(uid: Int, deviceId: String): SparseIntArray {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        service.getState {
            val modes =
                with(appIdPolicy) { opNameMapToOpSparseArray(getAppOpModes(appId, userId)?.map) }
            runtimePermissionNameToAppOp.forEachIndexed { _, permissionName, appOpCode ->
                val mode = getUidModeFromPermissionState(appId, userId, permissionName, deviceId)
                if (mode != AppOpsManager.opToDefaultMode(appOpCode)) {
                    modes[appOpCode] = mode
                }
            }

            return modes
        }
    }

    override fun getNonDefaultPackageModes(packageName: String, userId: Int): SparseIntArray {
        return opNameMapToOpSparseArray(getPackageModes(packageName, userId))
    }

    override fun getUidsWithOpMode(op: Int, mode: Int, userId: Int): List<Int> {
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState { with(appIdPolicy) { getUidsWithOpMode(opName, mode, userId) } }
    }

    override fun getUidMode(uid: Int, deviceId: String, op: Int): Int {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val opName = AppOpsManager.opToPublicName(op)
        val permissionName = runtimeAppOpToPermissionNames[op]

        return if (permissionName == null) {
            service.getState { with(appIdPolicy) { getAppOpMode(appId, userId, opName) } }
        } else {
            service.getState {
                if (isPrivacyGuarded(appId, userId, op)) {
                    MODE_IGNORED
                } else {
                    getUidModeFromPermissionState(appId, userId, permissionName, deviceId)
                }
            }
        }
    }

    private fun getUidModes(uid: Int): ArrayMap<String, Int>? {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        return service.getState { with(appIdPolicy) { getAppOpModes(appId, userId) } }?.map
    }

    private fun GetStateScope.getUidModeFromPermissionState(
        appId: Int,
        userId: Int,
        permissionName: String,
        deviceId: String,
    ): Int {
        val checkDevicePermissionFlags =
            deviceId != VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT &&
                permissionName in PermissionManager.DEVICE_AWARE_PERMISSIONS
        val permissionFlags =
            if (checkDevicePermissionFlags) {
                with(devicePermissionPolicy) {
                    getPermissionFlags(appId, deviceId, userId, permissionName)
                }
            } else {
                with(permissionPolicy) { getPermissionFlags(appId, userId, permissionName) }
            }
        val backgroundPermissionName = foregroundToBackgroundPermissionName[permissionName]
        val backgroundPermissionFlags =
            if (backgroundPermissionName != null) {
                if (checkDevicePermissionFlags) {
                    with(devicePermissionPolicy) {
                        getPermissionFlags(appId, deviceId, userId, backgroundPermissionName)
                    }
                } else {
                    with(permissionPolicy) {
                        getPermissionFlags(appId, userId, backgroundPermissionName)
                    }
                }
            } else {
                PermissionFlags.RUNTIME_GRANTED
            }
        val result = evaluateModeFromPermissionFlags(permissionFlags, backgroundPermissionFlags)
        if (result != MODE_IGNORED) {
            return result
        }

        val fullerPermissionName =
            PermissionService.getFullerPermission(permissionName) ?: return result
        return getUidModeFromPermissionState(appId, userId, fullerPermissionName, deviceId)
    }

    private fun evaluateModeFromPermissionFlags(
        foregroundFlags: Int,
        backgroundFlags: Int = PermissionFlags.RUNTIME_GRANTED,
    ): Int =
        if (PermissionFlags.isAppOpGranted(foregroundFlags)) {
            if (PermissionFlags.isAppOpGranted(backgroundFlags)) {
                MODE_ALLOWED
            } else {
                MODE_FOREGROUND
            }
        } else {
            MODE_IGNORED
        }

    override fun setUidMode(uid: Int, deviceId: String, code: Int, mode: Int): Boolean {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val appOpName = AppOpsManager.opToPublicName(code)

        if (code in runtimeAppOpToPermissionNames) {
            return false
        }

        var wasChanged: Boolean
        service.mutateState {
            wasChanged = with(appIdPolicy) { setAppOpMode(appId, userId, appOpName, mode) }
        }
        return wasChanged
    }

    override fun getPackageMode(packageName: String, op: Int, userId: Int): Int {
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState {
            with(packagePolicy) { getAppOpMode(packageName, userId, opName) }
        }
    }

    private fun getPackageModes(packageName: String, userId: Int): ArrayMap<String, Int>? =
        service.getState { with(packagePolicy) { getAppOpModes(packageName, userId) } }?.map

    override fun setPackageMode(packageName: String, appOpCode: Int, mode: Int, userId: Int) {
        val appOpName = AppOpsManager.opToPublicName(appOpCode)

        if (appOpCode in runtimeAppOpToPermissionNames) {
            Slog.w(
                LOG_TAG,
                "(packageName=$packageName, userId=$userId)'s appop state" +
                    " for runtime op $appOpName should not be set directly.",
                RuntimeException(),
            )
            return
        }
        service.mutateState {
            with(packagePolicy) { setAppOpMode(packageName, userId, appOpName, mode) }
        }
    }

    override fun removeUid(uid: Int) {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        service.mutateState { with(appIdPolicy) { removeAppOpModes(appId, userId) } }
    }

    override fun removePackage(packageName: String, userId: Int): Boolean {
        var wasChanged: Boolean
        service.mutateState {
            wasChanged = with(packagePolicy) { removeAppOpModes(packageName, userId) }
        }
        return wasChanged
    }

    private fun opNameMapToOpSparseArray(modes: ArrayMap<String, Int>?): SparseIntArray =
        if (modes == null) {
            SparseIntArray()
        } else {
            val opSparseArray = SparseIntArray(modes.size)
            modes.forEachIndexed { _, opName, opMode ->
                opSparseArray.put(AppOpsManager.strOpToOp(opName), opMode)
            }
            opSparseArray
        }

    override fun clearAllModes() {
        // We don't need to implement this because it's only called in AppOpsService#readState
        // and we have our own persistence.
    }

    override fun getForegroundOps(uid: Int, deviceId: String): SparseBooleanArray {
        return SparseBooleanArray().apply {
            getUidModes(uid)?.forEachIndexed { _, op, mode ->
                if (mode == AppOpsManager.MODE_FOREGROUND) {
                    this[AppOpsManager.strOpToOp(op)] = true
                }
            }
            foregroundableOps.forEachIndexed { _, op, _ ->
                if (getUidMode(uid, deviceId, op) == AppOpsManager.MODE_FOREGROUND) {
                    this[op] = true
                }
            }
        }
    }

    override fun getForegroundOps(packageName: String, userId: Int): SparseBooleanArray {
        return SparseBooleanArray().apply {
            getPackageModes(packageName, userId)?.forEachIndexed { _, op, mode ->
                if (mode == AppOpsManager.MODE_FOREGROUND) {
                    this[AppOpsManager.strOpToOp(op)] = true
                }
            }
            foregroundableOps.forEachIndexed { _, op, _ ->
                if (getPackageMode(packageName, op, userId) == AppOpsManager.MODE_FOREGROUND) {
                    this[op] = true
                }
            }
        }
    }

    override fun addAppOpsModeChangedListener(listener: AppOpsModeChangedListener): Boolean {
        synchronized(listenersLock) {
            val newListeners = ArraySet(listeners)
            val result = newListeners.add(listener)
            listeners = newListeners
            return result
        }
    }

    override fun removeAppOpsModeChangedListener(listener: AppOpsModeChangedListener): Boolean {
        synchronized(listenersLock) {
            val newListeners = ArraySet(listeners)
            val result = newListeners.remove(listener)
            listeners = newListeners
            return result
        }
    }

    private inner class OnAppIdAppOpModeChangedListener :
        AppIdAppOpPolicy.OnAppOpModeChangedListener() {
        // (uid, appOpCode) -> newMode
        private val pendingChanges = LongSparseArray<Int>()

        override fun onAppOpModeChanged(
            appId: Int,
            userId: Int,
            appOpName: String,
            oldMode: Int,
            newMode: Int,
        ) {
            val uid = UserHandle.getUid(userId, appId)
            val appOpCode = AppOpsManager.strOpToOp(appOpName)
            val key = IntPair.of(uid, appOpCode)

            pendingChanges[key] = newMode
        }

        override fun onStateMutated() {
            val listenersLocal = listeners
            pendingChanges.forEachIndexed { _, key, mode ->
                listenersLocal.forEachIndexed { _, listener ->
                    val uid = IntPair.first(key)
                    val appOpCode = IntPair.second(key)

                    listener.onUidModeChanged(
                        uid,
                        appOpCode,
                        mode,
                        VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                    )
                }
            }

            pendingChanges.clear()
        }
    }

    private inner class OnPackageAppOpModeChangedListener :
        PackageAppOpPolicy.OnAppOpModeChangedListener() {
        // (packageName, userId, appOpCode) -> newMode
        private val pendingChanges = ArrayMap<Triple<String, Int, Int>, Int>()

        override fun onAppOpModeChanged(
            packageName: String,
            userId: Int,
            appOpName: String,
            oldMode: Int,
            newMode: Int,
        ) {
            val appOpCode = AppOpsManager.strOpToOp(appOpName)
            val key = Triple(packageName, userId, appOpCode)

            pendingChanges[key] = newMode
        }

        override fun onStateMutated() {
            val listenersLocal = listeners
            pendingChanges.forEachIndexed { _, key, mode ->
                listenersLocal.forEachIndexed { _, listener ->
                    val packageName = key.first
                    val userId = key.second
                    val appOpCode = key.third

                    listener.onPackageModeChanged(packageName, userId, appOpCode, mode)
                }
            }

            pendingChanges.clear()
        }
    }

    private inner class OnPermissionFlagsChangedListener :
        AppIdPermissionPolicy.OnPermissionFlagsChangedListener,
        DevicePermissionPolicy.OnDevicePermissionFlagsChangedListener {
        // (uid, deviceId, appOpCode) -> newMode
        private val pendingChanges = ArrayMap<Triple<Int, String, Int>, Int>()

        override fun onPermissionFlagsChanged(
            appId: Int,
            userId: Int,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int,
        ) {
            onDevicePermissionFlagsChanged(
                appId,
                userId,
                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                permissionName,
                oldFlags,
                newFlags,
            )
        }

        override fun onDevicePermissionFlagsChanged(
            appId: Int,
            userId: Int,
            deviceId: String,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int,
        ) {
            backgroundToForegroundPermissionNames[permissionName]?.let { foregroundPermissions ->
                // This is a background permission; there may be multiple foreground permissions
                // affected.
                foregroundPermissions.forEachIndexed { _, foregroundPermissionName ->
                    runtimePermissionNameToAppOp[foregroundPermissionName]?.let { appOpCode ->
                        val foregroundPermissionFlags =
                            getPermissionFlags(appId, userId, foregroundPermissionName)
                        addPendingChangedModeIfNeeded(
                            appId,
                            userId,
                            deviceId,
                            appOpCode,
                            foregroundPermissionFlags,
                            oldFlags,
                            foregroundPermissionFlags,
                            newFlags,
                        )
                    }
                }
            }
                ?: foregroundToBackgroundPermissionName[permissionName]?.let { backgroundPermission
                    ->
                    runtimePermissionNameToAppOp[permissionName]?.let { appOpCode ->
                        val backgroundPermissionFlags =
                            getPermissionFlags(appId, userId, backgroundPermission)
                        addPendingChangedModeIfNeeded(
                            appId,
                            userId,
                            deviceId,
                            appOpCode,
                            oldFlags,
                            backgroundPermissionFlags,
                            newFlags,
                            backgroundPermissionFlags,
                        )
                    }
                }
                ?: runtimePermissionNameToAppOp[permissionName]?.let { appOpCode ->
                    addPendingChangedModeIfNeeded(
                        appId,
                        userId,
                        deviceId,
                        appOpCode,
                        oldFlags,
                        PermissionFlags.RUNTIME_GRANTED,
                        newFlags,
                        PermissionFlags.RUNTIME_GRANTED,
                    )
                }
        }

        private fun getPermissionFlags(appId: Int, userId: Int, permissionName: String): Int =
            service.getState {
                with(permissionPolicy) { getPermissionFlags(appId, userId, permissionName) }
            }

        private fun addPendingChangedModeIfNeeded(
            appId: Int,
            userId: Int,
            deviceId: String,
            appOpCode: Int,
            oldForegroundFlags: Int,
            oldBackgroundFlags: Int,
            newForegroundFlags: Int,
            newBackgroundFlags: Int,
        ) {
            val oldMode = evaluateModeFromPermissionFlags(oldForegroundFlags, oldBackgroundFlags)
            val newMode = evaluateModeFromPermissionFlags(newForegroundFlags, newBackgroundFlags)

            if (oldMode != newMode) {
                val uid = UserHandle.getUid(userId, appId)
                pendingChanges[Triple(uid, deviceId, appOpCode)] = newMode
            }
        }

        override fun onStateMutated() {
            val listenersLocal = listeners
            pendingChanges.forEachIndexed { _, key, mode ->
                listenersLocal.forEachIndexed { _, listener ->
                    val uid = key.first
                    val deviceId = key.second
                    val appOpCode = key.third

                    listener.onUidModeChanged(uid, appOpCode, mode, deviceId)
                }
            }

            pendingChanges.clear()
        }
    }

    companion object {
        private const val PRIVACY_GUARD_SETTING = "rm_privacy_guard"
        private const val PRIVACY_GUARD_CAMERA = 1
        private const val PRIVACY_GUARD_MIC = 2
        private const val PRIVACY_GUARD_LOCATION = 4
        private const val PRIVACY_GUARD_CONTACTS = 8
        private const val PRIVACY_GUARD_MEDIA_VISUAL = 16
        private const val PRIVACY_GUARD_MEDIA_AUDIO = 32
        private const val PRIVACY_GUARD_FILES = 64
        private const val PRIVACY_GUARD_NEARBY = 128
        private const val PRIVACY_GUARD_PHONE = 256
        private const val PRIVACY_GUARD_SMS = 512
        private const val PRIVACY_GUARD_CALENDAR = 1024
        private const val PRIVACY_GUARD_SENSORS = 2048
        // Bit 4096 (AD_ID) is handled by the AD_ID permission patch, not the app-op guard.
        private val LOG_TAG = AppOpService::class.java.simpleName
    }
}
