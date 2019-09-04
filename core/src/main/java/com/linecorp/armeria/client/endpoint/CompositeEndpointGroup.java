/*
 * Copyright 2019 LINE Corporation
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AbstractListenable;

/**
 * An {@link EndpointGroup} that merges the result of any number of other {@link EndpointGroup}s.
 */
final class CompositeEndpointGroup extends AbstractListenable<List<Endpoint>> implements EndpointGroup {

    private final List<EndpointGroup> endpointGroups;

    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture;

    private volatile List<Endpoint> merged = ImmutableList.of();

    private AtomicBoolean dirty;

    /**
     * Constructs a new {@link CompositeEndpointGroup} that merges all the given {@code endpointGroups}.
     */
    CompositeEndpointGroup(Iterable<EndpointGroup> endpointGroups) {
        requireNonNull(endpointGroups, "endpointGroups");
        this.endpointGroups = ImmutableList.copyOf(endpointGroups);
        dirty = new AtomicBoolean(true);

        for (EndpointGroup endpointGroup : endpointGroups) {
            endpointGroup.addListener(unused -> {
                dirty.set(true);
                notifyListeners(endpoints());
            });
        }

        initialEndpointsFuture =
                CompletableFuture.anyOf(this.endpointGroups.stream()
                                                           .map(EndpointGroup::initialEndpointsFuture)
                                                           .toArray(CompletableFuture[]::new))
                                 .thenApply(unused -> endpoints());
    }

    @Override
    public List<Endpoint> endpoints() {
        if (!dirty.get()) {
            return merged;
        }

        if (!dirty.compareAndSet(true, false)) {
            // Another thread might be updating merged at this time, but endpoint groups are allowed to take a
            // little bit of time to reflect updates.
            return merged;
        }

        ImmutableList.Builder<Endpoint> newEndpoints = ImmutableList.builder();
        for (EndpointGroup endpointGroup : endpointGroups) {
            newEndpoints.addAll(endpointGroup.endpoints());
        }

        return merged = newEndpoints.build();
    }

    @Override
    public CompletableFuture<List<Endpoint>> initialEndpointsFuture() {
        return initialEndpointsFuture;
    }

    @Override
    public void close() {
        Closer closer = Closer.create();
        for (EndpointGroup endpointGroup : endpointGroups) {
            closer.register(endpointGroup::close);
        }
        try {
            closer.close();
        } catch (IOException e) {
            // Can't happen since EndpointGroup is AutoCloseable, not Closeable.
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpointGroups", endpointGroups)
                          .toString();
    }
}
