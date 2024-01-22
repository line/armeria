/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.websocket;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * The result of a WebSocket upgrade.
 */
public final class WebSocketUpgradeResult {

    private static final WebSocketUpgradeResult SUCCESS = new WebSocketUpgradeResult(null);

    /**
     * Returns a successful {@link WebSocketUpgradeResult}.
     */
    public static WebSocketUpgradeResult ofSuccess() {
        return SUCCESS;
    }

    /**
     * Returns a failed {@link WebSocketUpgradeResult} with the fallback {@link HttpResponse}.
     */
    public static WebSocketUpgradeResult ofFailure(HttpResponse fallbackResponse) {
        requireNonNull(fallbackResponse, "failureResponse");
        return new WebSocketUpgradeResult(fallbackResponse);
    }

    @Nullable
    private final HttpResponse fallbackResponse;

    private WebSocketUpgradeResult(@Nullable HttpResponse fallbackResponse) {
        this.fallbackResponse = fallbackResponse;
    }

    /**
     * Returns {@code true} if the upgrade was successful.
     */
    public boolean isSuccess() {
        return fallbackResponse == null;
    }

    /**
     * Returns the fallback {@link HttpResponse} if the upgrade failed.
     *
     * @throws IllegalStateException if the upgrade was successful.
     */
    public HttpResponse fallbackResponse() {
        if (fallbackResponse == null) {
            throw new IllegalStateException("WebSocket was successfully upgraded.");
        }
        return fallbackResponse;
    }

    @Override
    public String toString() {
        if (isSuccess()) {
            return "WebSocketUpgradeResult(success)";
        }

        return MoreObjects.toStringHelper(this)
                          .add("fallback", fallbackResponse)
                          .toString();
    }
}
