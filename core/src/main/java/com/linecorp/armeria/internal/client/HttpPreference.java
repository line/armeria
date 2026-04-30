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

package com.linecorp.armeria.internal.client;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

public enum HttpPreference {
    HTTP1_REQUIRED,
    HTTP2_REQUIRED,
    /** Send the HTTP/2 connection preface directly. */
    PREFACE,
    /** Send an HTTP/1.1 upgrade request. */
    UPGRADE;

    public static HttpPreference of(SessionProtocol desiredProtocol, boolean useHttp2Preface) {
        if (desiredProtocol.isExplicitHttp1()) {
            return HTTP1_REQUIRED;
        } else if (desiredProtocol.isExplicitHttp2()) {
            return HTTP2_REQUIRED;
        } else if (useHttp2Preface) {
            return PREFACE;
        } else {
            return UPGRADE;
        }
    }

    public boolean isPreface() {
        return this == PREFACE || this == HTTP2_REQUIRED;
    }

    /**
     * Returns the next H2C preference to try when this one fails, or {@code null} if no fallback is available.
     *
     * @param useHttp2Preface whether the factory default is to use preface
     */
    @Nullable
    public HttpPreference nextFallback(boolean useHttp2Preface) {
        if (useHttp2Preface && this == PREFACE) {
            return UPGRADE;
        }
        if (!useHttp2Preface && this == UPGRADE) {
            return PREFACE;
        }
        if (this == PREFACE || this == UPGRADE) {
            return HTTP1_REQUIRED;
        }
        return null;
    }
}
