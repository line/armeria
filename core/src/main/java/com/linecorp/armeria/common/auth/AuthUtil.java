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

package com.linecorp.armeria.common.auth;

import com.linecorp.armeria.common.annotation.Nullable;

final class AuthUtil {

    /**
     * Compares two specified strings in the secure way.
     */
    static boolean secureEquals(@Nullable String a, @Nullable String b) {
        final int aLength = a != null ? a.length() : 0;
        final int bLength = b != null ? b.length() : 0;
        final int length = Math.min(aLength, bLength);
        int result = 0;
        for (int i = 0; i < length; i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0 && aLength == bLength;
    }

    private AuthUtil() {}
}
