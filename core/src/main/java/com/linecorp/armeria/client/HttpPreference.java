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

package com.linecorp.armeria.client;

import java.net.SocketAddress;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Specifies the HTTP/2 connection strategy to use when connecting to a server.
 */
@UnstableApi
public enum HttpPreference {
    HTTP1_REQUIRED,
    HTTP2_REQUIRED,
    /** Send the HTTP/2 connection preface directly. */
    PREFACE,
    /** Send an HTTP/1.1 upgrade request. */
    UPGRADE;

    static HttpPreference of(SessionProtocol desiredProtocol, boolean useHttp2Preface) {
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

    static HttpPreference of(SessionProtocol desiredProtocol, boolean useHttp2Preface,
                             SocketAddress remoteAddress) {
        HttpPreference pref = of(desiredProtocol, useHttp2Preface);
        while (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, pref)) {
            final HttpPreference next = pref.nextFallback(useHttp2Preface);
            if (next == null) {
                break;
            }
            pref = next;
        }
        return pref;
    }

    boolean isPreface() {
        return this == PREFACE || this == HTTP2_REQUIRED;
    }

    @Nullable
    HttpPreference nextFallback(boolean useHttp2Preface, SocketAddress remoteAddress) {
        HttpPreference next = nextFallback(useHttp2Preface);
        while (next != null && SessionProtocolNegotiationCache.isUnsupported(remoteAddress, next)) {
            next = next.nextFallback(useHttp2Preface);
        }
        return next;
    }

    @Nullable
    private HttpPreference nextFallback(boolean useHttp2Preface) {
        switch (this) {
            case PREFACE:
                return useHttp2Preface ? UPGRADE : HTTP1_REQUIRED;
            case UPGRADE:
                return useHttp2Preface ? HTTP1_REQUIRED : PREFACE;
            case HTTP1_REQUIRED:
            case HTTP2_REQUIRED:
                return null;
            default:
                throw new Error("Unexpected HttpPreference: " + this);
        }
    }
}
