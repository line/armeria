/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * A static immutable {@link EndpointGroup}.
 *
 * @deprecated use {@link EndpointGroup}
 */
@Deprecated
public final class StaticEndpointGroup implements EndpointGroup {

    static final StaticEndpointGroup EMPTY = new StaticEndpointGroup();

    private final List<Endpoint> endpoints;

    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture;

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link EndpointGroup#of(EndpointGroup...)}.
     */
    @Deprecated
    public StaticEndpointGroup(Endpoint... endpoints) {
        this(ImmutableList.copyOf(requireNonNull(endpoints, "endpoints")));
    }

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link EndpointGroup#of(Iterable)}.
     */
    @Deprecated
    public StaticEndpointGroup(Iterable<Endpoint> endpoints) {
        requireNonNull(endpoints, "endpoints");

        this.endpoints = ImmutableList.copyOf(endpoints);

        initialEndpointsFuture = CompletableFuture.completedFuture(this.endpoints);
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public CompletableFuture<List<Endpoint>> initialEndpointsFuture() {
        return initialEndpointsFuture;
    }

    @Override
    public String toString() {
        return StaticEndpointGroup.class.getSimpleName() + endpoints;
    }
}
