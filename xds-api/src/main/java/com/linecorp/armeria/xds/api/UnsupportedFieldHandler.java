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

package com.linecorp.armeria.xds.api;

import static com.linecorp.armeria.xds.api.SupportedFieldValidator.unsupportedLogger;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A handler that is invoked when unsupported xDS fields are detected in a protobuf message.
 * Unsupported fields are those not annotated with {@code (armeria.xds.supported) = true}.
 */
@UnstableApi
@FunctionalInterface
public interface UnsupportedFieldHandler {

    /**
     * Called when an unsupported field is detected.
     *
     * @param descriptorName the full name of the root message being validated
     *                       (e.g., {@code "envoy.config.cluster.v3.Cluster"})
     * @param fieldPath the JSON path of the unsupported field (e.g., {@code "$.edsConfig.serviceName"})
     * @param value the raw value of the unsupported field
     */
    void handle(String descriptorName, String fieldPath, Object value);

    /**
     * Returns a composed handler that first invokes this handler, then the {@code after} handler.
     */
    default UnsupportedFieldHandler andThen(UnsupportedFieldHandler after) {
        requireNonNull(after, "after");
        return (descriptorName, fieldPath, value) -> {
            handle(descriptorName, fieldPath, value);
            after.handle(descriptorName, fieldPath, value);
        };
    }

    /**
     * Returns a handler that logs a warning for each unsupported field path.
     */
    static UnsupportedFieldHandler warn() {
        return (descriptorName, fieldPath, value) ->
                unsupportedLogger.warn("Unsupported xDS field detected in {}: {}", descriptorName, fieldPath);
    }

    /**
     * Returns a handler that throws an {@link IllegalArgumentException} on the first unsupported field.
     */
    static UnsupportedFieldHandler reject() {
        return (descriptorName, fieldPath, value) -> {
            throw new IllegalArgumentException(
                    "Unsupported xDS field detected in " + descriptorName + ": " + fieldPath);
        };
    }

    /**
     * Returns a handler that silently ignores unsupported fields.
     */
    static UnsupportedFieldHandler ignore() {
        return IgnoreUnsupportedFieldHandler.INSTANCE;
    }
}
