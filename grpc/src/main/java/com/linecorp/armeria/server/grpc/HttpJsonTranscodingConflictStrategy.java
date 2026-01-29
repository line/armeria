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

import org.slf4j.LoggerFactory;

import com.google.api.HttpRule;
import com.google.protobuf.Descriptors;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A strategy that resolves conflicts when multiple {@link HttpRule}s target the same gRPC method.
 *
 * <p>Conflicts are resolved at the method level before routes are registered.
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
     */
    static HttpJsonTranscodingConflictStrategy firstWins() {
        return (method, oldRule, newRule) -> {
            LoggerFactory.getLogger(HttpJsonTranscodingConflictStrategy.class)
                         .debug("Ignoring new HttpRule: {} for gRPC method: {}. Keeping existing HttpRule: {}",
                                newRule, method.getFullName(), oldRule);
            return oldRule;
        };
    }

    /**
     * Returns a strategy that always replaces the existing {@link HttpRule} with the new one.
     */
    static HttpJsonTranscodingConflictStrategy lastWins() {
        return (method, oldRule, newRule) -> {
            LoggerFactory.getLogger(HttpJsonTranscodingConflictStrategy.class)
                         .debug("Replacing existing HttpRule: {} for gRPC method: {} with new HttpRule: {}",
                                oldRule, method.getFullName(), newRule);
            return newRule;
        };
    }

    /**
     * Returns a strategy that throws an {@link IllegalArgumentException} when a conflict is detected.
     * This is the default behavior.
     */
    static HttpJsonTranscodingConflictStrategy strict() {
        return (method, oldRule, newRule) -> {
            throw new IllegalArgumentException(
                    "Duplicate HttpRule detected for gRPC method: " + method.getFullName() +
                    ". Existing: " + oldRule + ", New: " + newRule);
        };
    }

    /**
     * Resolves which {@link HttpRule} to use when multiple rules target the same gRPC method.
     *
     * <p>This method is invoked with the {@link Descriptors.MethodDescriptor} where the conflict occurred,
     * the {@link HttpRule} that was registered first (oldRule), and the {@link HttpRule} that is being added
     * (newRule).
     */
    HttpRule resolve(Descriptors.MethodDescriptor method, HttpRule oldRule, HttpRule newRule);
}
