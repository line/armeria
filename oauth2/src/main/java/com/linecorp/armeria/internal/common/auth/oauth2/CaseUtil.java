/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.common.auth.oauth2;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.annotation.Nullable;

public final class CaseUtil {

    @Nullable
    public static String firstUpperCase(@Nullable String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        return Ascii.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    @Nullable
    public static String firstUpperAllLowerCase(@Nullable String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        return Ascii.toUpperCase(word.charAt(0)) + Ascii.toLowerCase(word.substring(1));
    }

    private CaseUtil() {
    }
}
