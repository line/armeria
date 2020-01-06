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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AbstractListenable;

final class OrElseEndpointGroup extends AbstractListenable<List<Endpoint>> implements EndpointGroup {
    private final EndpointGroup first;
    private final EndpointGroup second;

    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture;
    private final EndpointSelector selector;

    OrElseEndpointGroup(EndpointGroup first, EndpointGroup second) {
        this.first = requireNonNull(first, "first");
        this.second = requireNonNull(second, "second");
        first.addListener(unused -> notifyListeners(endpoints()));
        second.addListener(unused -> notifyListeners(endpoints()));

        initialEndpointsFuture = CompletableFuture
                .anyOf(first.initialEndpointsFuture(), second.initialEndpointsFuture())
                .thenApply(unused -> endpoints());

        selector = first.selectionStrategy().newSelector(this);
    }

    @Override
    public List<Endpoint> endpoints() {
        final List<Endpoint> endpoints = first.endpoints();
        if (!endpoints.isEmpty()) {
            return endpoints;
        }
        return second.endpoints();
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return first.selectionStrategy();
    }

    @Override
    public Endpoint select(ClientRequestContext ctx) {
        return selector.select(ctx);
    }

    @Override
    public CompletableFuture<List<Endpoint>> initialEndpointsFuture() {
        return initialEndpointsFuture;
    }

    @Override
    public void close() {
        try (EndpointGroup first = this.first;
             EndpointGroup second = this.second) {
            // Just want to ensure closure.
        }
    }
}
