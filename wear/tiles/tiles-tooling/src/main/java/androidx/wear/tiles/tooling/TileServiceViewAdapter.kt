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

package androidx.wear.tiles.tooling

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.StateBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.renderer.TileRenderer
import androidx.wear.tiles.timeline.TilesTimelineCache
import androidx.wear.tiles.tooling.preview.TilePreviewData
import java.lang.reflect.Method
import kotlin.math.roundToInt
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking

private const val TOOLS_NS_URI = "http://schemas.android.com/tools"

/**
 * A method extending functionality of [Class.getDeclaredMethod] allowing to finding the methods
 * (including non-public) declared in the superclasses as well.
 */
internal fun Class<out Any>.findMethod(
    name: String,
    vararg parameterTypes: Class<out Any>
): Method {
    var currentClass: Class<out Any>? = this
    while (currentClass != null) {
        try {
            return currentClass.getDeclaredMethod(name, *parameterTypes)
        } catch (_: NoSuchMethodException) { }
        currentClass = currentClass.superclass
    }
    val methodSignature = "$name(${parameterTypes.joinToString { ", " }})"
    throw NoSuchMethodException(
        "Could not find method $methodSignature neither in $this nor in its superclasses.")
}

/**
 * View adapter that renders a tile preview from a [TilePreviewData]. The preview data is found by
 * invoking the method whose FQN is set in the `tools:tilePreviewMethodFqn` attribute.
 */
internal class TileServiceViewAdapter(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs) {
    init {
        init(attrs)
    }

    private fun init(attrs: AttributeSet) {
        val tilePreviewMethodFqn = attrs.getAttributeValue(TOOLS_NS_URI, "tilePreviewMethodFqn")
            ?: return

        init(tilePreviewMethodFqn)
    }

    internal fun init(tilePreviewMethodFqn: String) {
        val tilePreview = getTilePreview(tilePreviewMethodFqn)
        lateinit var tileRenderer: TileRenderer
        tileRenderer = TileRenderer(context, ContextCompat.getMainExecutor(context)) { newState ->
            tileRenderer.previewTile(tilePreview, newState)
        }
        tileRenderer.previewTile(tilePreview)
    }

    private fun TileRenderer.previewTile(
        tilePreview: TilePreviewData,
        currentState: StateBuilders.State? = null
    ) {
        val deviceParams = context.buildDeviceParameters()
        val tileRequest = RequestBuilders.TileRequest
            .Builder()
            .apply {
                currentState?.let { setCurrentState(it) }
            }
            .setDeviceConfiguration(deviceParams)
            .build()

        val tile = tilePreview.onTileRequest(tileRequest, context).also { tile ->
            tile.state?.let { setState(it.keyToValueMapping) }
        }
        val layout = tile.tileTimeline?.getCurrentLayout() ?: return

        val resourcesRequest = ResourcesRequest.Builder()
            .setDeviceConfiguration(deviceParams)
            .setVersion(tile.resourcesVersion)
            .build()
        val resources = tilePreview.onTileResourceRequest(resourcesRequest, context)

        runBlocking {
            inflateAsync(layout, resources, this@TileServiceViewAdapter)
                .await()
                ?.apply { (layoutParams as LayoutParams).gravity = Gravity.CENTER }
        }
    }
}

@SuppressLint("BanUncheckedReflection")
internal fun getTilePreview(tilePreviewMethodFqn: String): TilePreviewData {
    val className = tilePreviewMethodFqn.substringBeforeLast('.')
    val methodName = tilePreviewMethodFqn.substringAfterLast('.')

    val method = Class.forName(className).declaredMethods.first {
        it.name == methodName && it.parameterCount == 0
    }.apply {
        isAccessible = true
    }

    return method.invoke(null) as TilePreviewData
}

internal fun TimelineBuilders.Timeline?.getCurrentLayout(): LayoutElementBuilders.Layout? {
    val now = System.currentTimeMillis()
    return this?.let {
        val cache = TilesTimelineCache(it)
        cache.findTileTimelineEntryForTime(now) ?: cache.findClosestTileTimelineEntry(now)
    }?.layout
}

/**
 * Creates an instance of [DeviceParametersBuilders.DeviceParameters] from the [Context].
 */
internal fun Context.buildDeviceParameters(): DeviceParametersBuilders.DeviceParameters {
    val displayMetrics = resources.displayMetrics
    val isScreenRound = resources.configuration.isScreenRound
    return DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenWidthDp(
            (displayMetrics.widthPixels / displayMetrics.density).roundToInt())
        .setScreenHeightDp(
            (displayMetrics.heightPixels / displayMetrics.density).roundToInt())
        .setScreenDensity(displayMetrics.density)
        .setScreenShape(
            if (isScreenRound) DeviceParametersBuilders.SCREEN_SHAPE_ROUND
            else DeviceParametersBuilders.SCREEN_SHAPE_RECT
        )
        .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
        .setFontScale(resources.configuration.fontScale)
        .build()
}
