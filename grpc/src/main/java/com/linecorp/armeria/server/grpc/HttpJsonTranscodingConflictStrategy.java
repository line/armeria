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

package com.linecorp.armeria.server.grpc;

import com.google.api.HttpRule;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A strategy that resolves conflicts when multiple {@link HttpRule}s target the same gRPC method selector.
 *
 * <p>Conflicts are resolved at the selector level (the full gRPC method name) before routes are registered.
 *
 * <p>Note that this strategy specifically handles cases where the same gRPC method is configured multiple
 * times (e.g., via both Proto annotations and programmatic configuration). It does not resolve conflicts where
 * two <em>different</em> gRPC methods map to the exact same HTTP path and method. Such route conflicts are
 * not allowed and will always raise an exception during registration.
 */
@UnstableApi
@FunctionalInterface
public interface HttpJsonTranscodingConflictStrategy {

    /**
     * Returns a strategy that always keeps the existing {@link HttpRule} and ignores the new one.
     * This is the default behavior.
     */
    static HttpJsonTranscodingConflictStrategy firstWins() {
        return (selector, oldRule, newRule) -> oldRule;
    }

    /**
     * Returns a strategy that always replaces the existing {@link HttpRule} with the new one.
     */
    static HttpJsonTranscodingConflictStrategy lastWins() {
        return (selector, oldRule, newRule) -> newRule;
    }

    /**
     * Resolves which {@link HttpRule} to use when multiple rules target the same gRPC method selector.
     *
     * <p>This method is invoked with the gRPC method selector (full method name) where the conflict
     * occurred, the {@link HttpRule} that was registered first (oldRule), and the {@link HttpRule}
     * that is being added (newRule).
     */
    HttpRule resolve(String selector, HttpRule oldRule, HttpRule newRule);
}
