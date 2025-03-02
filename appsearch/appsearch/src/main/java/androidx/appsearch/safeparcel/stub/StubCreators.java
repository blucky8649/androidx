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
package androidx.appsearch.safeparcel.stub;

import androidx.annotation.RestrictTo;

/**
 * Stub creators for any classes extending
 * {@link androidx.appsearch.safeparcel.SafeParcelable}.
 *
 * <p>We don't have SafeParcelProcessor in Jetpack, so for each
 * {@link androidx.appsearch.safeparcel.SafeParcelable}, a stub creator class needs to
 * be provided for code sync purpose.
 */
// @exportToFramework:skipFile()
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class StubCreators {
    /** Stub creator for {@link androidx.appsearch.app.StorageInfo}. */
    public static class StorageInfoCreator extends AbstractCreator {
    }

    /** Stub creator for {@link androidx.appsearch.app.PropertyParcel}. */
    public static class PropertyParcelCreator extends AbstractCreator {
    }
}
