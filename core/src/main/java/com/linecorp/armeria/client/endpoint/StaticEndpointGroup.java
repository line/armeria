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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * A static immutable {@link EndpointGroup}.
 */
public final class StaticEndpointGroup implements EndpointGroup {

    private final List<Endpoint> endpoints;

    /**
     * Creates a new instance.
     */
    public StaticEndpointGroup(Endpoint... endpoints) {
        requireNonNull(endpoints, "endpoints");

        this.endpoints = ImmutableList.copyOf(endpoints);
    }

    /**
     * Creates a new instance.
     */
    public StaticEndpointGroup(Iterable<Endpoint> endpoints) {
        requireNonNull(endpoints, "endpoints");

        this.endpoints = ImmutableList.copyOf(endpoints);
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("StaticEndpointGroup(");
        for (Endpoint endpoint : endpoints) {
            buf.append(endpoint).append(',');
        }
        buf.setCharAt(buf.length() - 1, ')');

        return buf.toString();
    }
}
