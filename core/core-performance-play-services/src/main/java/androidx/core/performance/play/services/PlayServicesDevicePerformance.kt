/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.core.performance.play.services

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.performance.DefaultDevicePerformance
import androidx.core.performance.DevicePerformance
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.deviceperformance.DevicePerformanceClient
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A DevicePerformance that uses Google Play Services to retrieve media performance class data.
 *
 * @param context The application context value to use.
 */
class PlayServicesDevicePerformance(private val context: Context) : DevicePerformance {
    private val tag = "PlayServicesDevicePerformance"

    private val defaultMpc = DefaultDevicePerformance()
    private val playServicesValueStoredDeferred = CompletableDeferred<Boolean>()

    override val mediaPerformanceClass get() = lazyMpc.value
    private val lazyMpc =
        lazy {
            runBlocking {
                val storedMpc = getPerformanceClass().first()
                Log.v(tag, "Stored mpc is $storedMpc")
                Log.v(tag, "Default mpc is ${defaultMpc.mediaPerformanceClass}")
                val returnedMpc = max(storedMpc ?: 0, defaultMpc.mediaPerformanceClass)
                Log.v(tag, "Mpc value used $returnedMpc")
                return@runBlocking returnedMpc
            }
        }

    init {
        Log.v(
            tag,
            "Getting mediaPerformanceClass from " +
                "com.google.android.gms.deviceperformance.DevicePerformanceClient"
        )
        updatePerformanceStore(
            com.google.android.gms.deviceperformance.DevicePerformance.getClient(context)
        )
    }

    @VisibleForTesting
    internal constructor(context: Context, client: DevicePerformanceClient) : this(context) {
        // mock client should wait for the playServices client to finish,
        // so the test results are determined by the mock client.
        runBlocking {
            playServicesValueStoredDeferred.await()
        }
        updatePerformanceStore(client)
    }

    private val mpcKey = intPreferencesKey("mpc_value")

    internal companion object {
        // To avoid creating multiple instance of datastore
        private val Context.performanceStore by
        preferencesDataStore(name = "media_performance_class")

        @VisibleForTesting
        suspend fun clearPerformanceClass(context: Context) {
            context.performanceStore.edit {
                it.clear()
            }
        }
    }

    private fun getPerformanceClass(): Flow<Int?> {
        return context.performanceStore.data.map { values ->
            // No type safety.
            values[mpcKey]
        }
    }

    private suspend fun savePerformanceClass(value: Int) {
        context.performanceStore.edit { values ->
            values[mpcKey] = value
        }
    }

    private fun updatePerformanceStore(client: DevicePerformanceClient) {
        client.mediaPerformanceClass().addOnSuccessListener { result ->
            runBlocking {
                Log.v(tag, "Got mediaPerformanceClass $result")
                val storedVal = max(result, defaultMpc.mediaPerformanceClass)
                launch {
                    savePerformanceClass(storedVal)
                    Log.v(tag, "Saved mediaPerformanceClass $storedVal")
                    playServicesValueStoredDeferred.complete(true)
                }
            }
        }.addOnFailureListener { e: Exception ->
            if (e is ApiException) {
                Log.e(tag, "Error saving mediaPerformanceClass: $e")
            } else if (e is IllegalStateException) {
                Log.e(tag, "Error saving mediaPerformanceClass: $e")
            }
            playServicesValueStoredDeferred.complete(true)
        }
    }
}
