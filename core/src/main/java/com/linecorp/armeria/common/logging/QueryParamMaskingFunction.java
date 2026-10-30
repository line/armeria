/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.common.logging;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A function that masks the specified query parameter value.
 */
@UnstableApi
@FunctionalInterface
public interface QueryParamMaskingFunction {

    /**
     * Returns the default {@link QueryParamMaskingFunction} that masks the given value with {@code ****}.
     */
    static QueryParamMaskingFunction of() {
        return (name, value) -> "****";
    }

    /**
     * Masks the specified {@code value} of the specified {@code name}.
     * If {@code null} is returned, the specified query parameter will be removed from the log.
     */
    @Nullable
    String mask(String name, String value);
}
