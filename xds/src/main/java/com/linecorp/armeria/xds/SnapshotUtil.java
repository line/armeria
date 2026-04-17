/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.xds;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.annotation.Nullable;

final class SnapshotUtil {

    @Nullable
    static <T> String debugString(@Nullable T obj, Function<T, String> toDebugString) {
        return obj != null ? toDebugString.apply(obj) : null;
    }

    static <T> List<String> debugStrings(Collection<T> items, Function<T, String> toDebugString) {
        return items.stream().map(toDebugString).collect(Collectors.toList());
    }

    private SnapshotUtil() {}
}
