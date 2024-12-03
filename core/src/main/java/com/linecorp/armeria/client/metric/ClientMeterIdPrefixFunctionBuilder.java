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
package com.linecorp.armeria.client.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

/**
 * Builds a {@link DefaultClientMeterIdPrefixFunction}.
 */
public final class ClientMeterIdPrefixFunctionBuilder {

    private static final Set<String> DEFAULT_TAGS = ImmutableSet.of(
            "service", "method", "httpStatus");

    private static final Set<String> ALL_TAGS = ImmutableSet.of(
            "service", "method", "httpStatus", "remoteAddress");

    private final String name;

    private final Set<String> tags = new HashSet<>(DEFAULT_TAGS);

    ClientMeterIdPrefixFunctionBuilder(String name) {
        this.name = requireNonNull(name, "name");
    }

    /**
     * Adds the specified tags to the set of tags to be included in the {@link MeterIdPrefix}.
     * Currently supported tags are:
     * <ul>
     *   <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                        available</li>
     *   <li>{@code service} - RPC service name or innermost service class name</li>
     *   <li>{@code remoteAddress} - the remote address that the client connects to in the form of
     *                               {@code host/IP:port}</li>
     *   <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     * </ul>
     *
     * <p><strong>Note:</strong> The {@code method}, {@code service}, and {@code httpStatus} tags are
     * included by default. Exercise caution when adding the {@code remoteAddress} tag, as it may result
     * in a large number of distinct metric IDs if the client connects to many different remote addresses
     * (e.g., when using Client-Side Load Balancing (CSLB)). The {@code remoteAddress} value is created using
     * the {@link Endpoint} of the client, so it doesn't contain the IP address if the {@link Endpoint} doesn't
     * have the IP address.
     *
     * <p>Additionally, ensure that meters with the same name have the same set of tags. Use different
     * meter names for clients with differing tag sets, for example: {@code "armeria.client"} and
     * {@code "armeria.cslb.client"}.
     */
    public ClientMeterIdPrefixFunctionBuilder includeTags(String... tags) {
        return includeTags(ImmutableSet.copyOf(requireNonNull(tags, "tags")));
    }

    /**
     * Adds the specified tags to the set of tags to be included in the {@link MeterIdPrefix}.
     * Currently supported tags are:
     * <ul>
     *   <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                        available</li>
     *   <li>{@code service} - RPC service name or innermost service class name</li>
     *   <li>{@code remoteAddress} - the remote address that the client connects to</li>
     *   <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     * </ul>
     *
     * <p><strong>Note:</strong> The {@code method}, {@code service}, and {@code httpStatus} tags are
     * included by default. Exercise caution when adding the {@code remoteAddress} tag, as it may result
     * in a large number of distinct metric IDs if the client connects to many different remote addresses
     * (e.g., when using Client-Side Load Balancing (CSLB)). The {@code remoteAddress} value is created using
     * the {@link Endpoint} of the client, so it doesn't contain the IP address if the {@link Endpoint} doesn't
     * have the IP address.
     *
     * <p>Additionally, ensure that meters with the same name have the same set of tags. Use different
     * meter names for clients with differing tag sets, for example: {@code "armeria.client"} and
     * {@code "armeria.cslb.client"}.
     */
    public ClientMeterIdPrefixFunctionBuilder includeTags(Iterable<String> tags) {
        return addOrRemove(tags, true);
    }

    /**
     * Removes the specified tags from the set of tags included in the {@link MeterIdPrefix}.
     * The currently supported tags that can be excluded are:
     * <ul>
     *   <li>{@code method} - The RPC method name, or {@link HttpMethod#name()} if the RPC method name
     *                        is unavailable.</li>
     *   <li>{@code service} - The RPC service name, or the innermost service class name.</li>
     *   <li>{@code remoteAddress} - The remote address that the client connects to.</li>
     *   <li>{@code httpStatus} - The HTTP status code ({@link HttpStatus#code()}).</li>
     * </ul>
     */
    public ClientMeterIdPrefixFunctionBuilder excludeTags(String... tags) {
        return excludeTags(ImmutableSet.copyOf(requireNonNull(tags, "tags")));
    }

    /**
     * Removes the specified tags from the set of tags included in the {@link MeterIdPrefix}.
     * The currently supported tags that can be excluded are:
     * <ul>
     *   <li>{@code method} - The RPC method name, or {@link HttpMethod#name()} if the RPC method name
     *                        is unavailable.</li>
     *   <li>{@code service} - The RPC service name, or the innermost service class name.</li>
     *   <li>{@code remoteAddress} - The remote address that the client connects to.</li>
     *   <li>{@code httpStatus} - The HTTP status code ({@link HttpStatus#code()}).</li>
     * </ul>
     */
    public ClientMeterIdPrefixFunctionBuilder excludeTags(Iterable<String> tags) {
        return addOrRemove(tags, false);
    }

    private ClientMeterIdPrefixFunctionBuilder addOrRemove(Iterable<String> tags, boolean add) {
        final Set<String> tags0 = ImmutableSet.copyOf(requireNonNull(tags, "tags"));
        for (String tag : tags0) {
            checkArgument(ALL_TAGS.contains(tag), "unknown tag: %s, (expected: one of %s)", tag, ALL_TAGS);
        }

        if (add) {
            this.tags.addAll(tags0);
        } else {
            this.tags.removeAll(tags0);
        }
        return this;
    }

    /**
     * Builds a new {@link ClientMeterIdPrefixFunction} with the configured settings.
     */
    public ClientMeterIdPrefixFunction build() {
        checkState(!tags.isEmpty(), "tags is empty.");
        final boolean includeHttpStatus = tags.contains("httpStatus");
        final boolean includeMethod = tags.contains("method");
        final boolean includeRemoteAddress = tags.contains("remoteAddress");
        final boolean includeService = tags.contains("service");
        return new DefaultClientMeterIdPrefixFunction(name, includeHttpStatus, includeMethod,
                                                      includeRemoteAddress, includeService);
    }
}
