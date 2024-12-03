/*
 *  Copyright 2024 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.server.VirtualHost;

/**
 * Builds a {@link DefaultServerMeterIdPrefixFunction}.
 */
public final class ServerMeterIdPrefixFunctionBuilder {

    private static final Set<String> DEFAULT_TAGS = ImmutableSet.of(
            "hostnamePattern", "service", "method", "httpStatus");

    private final String name;

    private final Set<String> tags = new HashSet<>(DEFAULT_TAGS);

    ServerMeterIdPrefixFunctionBuilder(String name) {
        this.name = requireNonNull(name, "name");
    }

    /**
     * Adds the specified tags to the set of tags to be included in the {@link MeterIdPrefix}.
     * Currently supported tags are:
     * <ul>
     *   <li>{@code hostnamePattern} - {@link VirtualHost#hostnamePattern()}
     *   <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                        available</li>
     *   <li>{@code service} - RPC service name or innermost service class name</li>
     *   <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     * </ul>
     *
     * <p>Ensure that meters with the same name have the same set of tags, otherwise an exception will be
     * thrown.
     */
    public ServerMeterIdPrefixFunctionBuilder includeTags(String... tags) {
        return includeTags(ImmutableSet.copyOf(requireNonNull(tags, "tags")));
    }

    /**
     * Adds the specified tags to the set of tags to be included in the {@link MeterIdPrefix}.
     * Currently supported tags are:
     * <ul>
     *   <li>{@code hostnamePattern} - {@link VirtualHost#hostnamePattern()}
     *   <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                        available</li>
     *   <li>{@code service} - RPC service name or innermost service class name</li>
     *   <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     * </ul>
     *
     * <p>Ensure that meters with the same name have the same set of tags, otherwise an exception will be
     * thrown.
     */
    public ServerMeterIdPrefixFunctionBuilder includeTags(Iterable<String> tags) {
        return addOrRemove(tags, true);
    }

    /**
     * Removes the specified tags from the set of tags included in the {@link MeterIdPrefix}.
     * The currently supported tags that can be excluded are:
     * <ul>
     *   <li>{@code hostnamePattern} - {@link VirtualHost#hostnamePattern()}
     *   <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                        available</li>
     *   <li>{@code service} - RPC service name or innermost service class name</li>
     *   <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     * </ul>
     */
    public ServerMeterIdPrefixFunctionBuilder excludeTags(String... tags) {
        return excludeTags(ImmutableSet.copyOf(requireNonNull(tags, "tags")));
    }

    /**
     * Removes the specified tags from the set of tags included in the {@link MeterIdPrefix}.
     * The currently supported tags that can be excluded are:
     * <ul>
     *   <li>{@code hostnamePattern} - {@link VirtualHost#hostnamePattern()}
     *   <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                        available</li>
     *   <li>{@code service} - RPC service name or innermost service class name</li>
     *   <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     * </ul>
     */
    public ServerMeterIdPrefixFunctionBuilder excludeTags(Iterable<String> tags) {
        return addOrRemove(tags, false);
    }

    private ServerMeterIdPrefixFunctionBuilder addOrRemove(Iterable<String> tags, boolean add) {
        final Set<String> tags0 = ImmutableSet.copyOf(requireNonNull(tags, "tags"));
        for (String tag : tags0) {
            checkArgument(DEFAULT_TAGS.contains(tag), "unknown tag: %s, (expected: one of %s)",
                          tag, DEFAULT_TAGS);
        }

        if (add) {
            this.tags.addAll(tags0);
        } else {
            this.tags.removeAll(tags0);
        }
        return this;
    }

    /**
     * Builds a new {@link ServerMeterIdPrefixFunction} with the configured settings.
     */
    public ServerMeterIdPrefixFunction build() {
        checkState(!tags.isEmpty(), "tags is empty.");
        final boolean includeHostnamePattern = tags.contains("hostnamePattern");
        final boolean includeHttpStatus = tags.contains("httpStatus");
        final boolean includeMethod = tags.contains("method");
        final boolean includeService = tags.contains("service");
        return new DefaultServerMeterIdPrefixFunction(name, includeHostnamePattern, includeHttpStatus,
                                                      includeMethod, includeService);
    }
}
