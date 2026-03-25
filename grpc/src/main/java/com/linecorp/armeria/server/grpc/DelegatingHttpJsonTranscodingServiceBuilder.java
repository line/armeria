/*
 * Copyright 2025 LY Corporation
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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;

import io.grpc.ServiceDescriptor;

/**
 * Builds a {@link DelegatingHttpJsonTranscodingService} that transcodes HTTP/JSON requests to
 * gRPC and delegates them to the specified {@link HttpService}.
 *
 * <p>At least one {@link ServiceDescriptor} must be added. The descriptor should be backed by
 * Protobuf so that {@code google.api.http} annotations can be resolved. If no HTTP rules are
 * discovered (either via annotations or {@link HttpJsonTranscodingOptions#additionalHttpRules()}),
 * {@link #build()} throws.
 */
@UnstableApi
public final class DelegatingHttpJsonTranscodingServiceBuilder {

    private final ImmutableSet.Builder<ServiceDescriptor> serviceDescriptorsBuilder = ImmutableSet.builder();
    private final HttpService delegate;

    private HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.of();

    /**
     * Creates a new builder for the specified delegate.
     */
    DelegatingHttpJsonTranscodingServiceBuilder(HttpService delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Sets the {@link HttpJsonTranscodingOptions} to customize rule discovery, conflict handling,
     * query parameter mapping, and error handling.
     */
    public DelegatingHttpJsonTranscodingServiceBuilder options(HttpJsonTranscodingOptions options) {
        this.options = requireNonNull(options, "options");
        return this;
    }

    /**
     * Adds the {@link ServiceDescriptor}s that define HTTP/JSON mappings.
     */
    public DelegatingHttpJsonTranscodingServiceBuilder serviceDescriptors(
            ServiceDescriptor... serviceDescriptors) {
        requireNonNull(serviceDescriptors, "serviceDescriptors");
        serviceDescriptors(ImmutableList.copyOf(serviceDescriptors));
        return this;
    }

    /**
     * Adds the {@link ServiceDescriptor}s that define HTTP/JSON mappings.
     */
    public DelegatingHttpJsonTranscodingServiceBuilder serviceDescriptors(
            Iterable<ServiceDescriptor> serviceDescriptors) {
        requireNonNull(serviceDescriptors, "serviceDescriptors");
        for (ServiceDescriptor serviceDescriptor : serviceDescriptors) {
            serviceDescriptorsBuilder.add(serviceDescriptor);
        }
        return this;
    }

    /**
     * Builds a new {@link DelegatingHttpJsonTranscodingService}.
     *
     * @throws IllegalStateException if no HTTP rules are configured
     */
    public DelegatingHttpJsonTranscodingService build() {
        final Set<ServiceDescriptor> serviceDescriptors = serviceDescriptorsBuilder.build();
        checkState(!serviceDescriptors.isEmpty(), "serviceDescriptors must be set.");
        final HttpJsonTranscoder transcoder =
                new HttpJsonTranscoderBuilder()
                        .options(options)
                        .serviceDescriptors(serviceDescriptors)
                        .build();
        if (transcoder == null) {
            throw new IllegalStateException("No HTTP rules are configured.");
        }
        return new DelegatingHttpJsonTranscodingService(delegate, transcoder);
    }
}
