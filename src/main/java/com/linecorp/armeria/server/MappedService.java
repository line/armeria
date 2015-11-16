/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

/**
 * A {@link Service} mapped by {@link ServiceMapping}.
 */
public final class MappedService implements Service {

    private static final MappedService EMPTY = new MappedService(null, null);

    /**
     * Returns a singleton instance of a {@link MappedService} that represents a non-existent {@link Service}.
     */
    public static MappedService empty() {
        return EMPTY;
    }

    /**
     * Creates a new {@link MappedService} with the specified {@code mappedPath} and {@link Service}.
     *
     * @param mappedPath the path translated by {@link PathMapping}
     * @param service the {@link Service}
     */
    public static MappedService of(String mappedPath, Service service) {
        return new MappedService(requireNonNull(mappedPath, "mappedPath"),
                                 requireNonNull(service, "service"));
    }

    private final String mappedPath;
    private final Service service;

    private MappedService(String mappedPath, Service service) {
        assert mappedPath != null && service != null ||
               mappedPath == null && service == null;

        this.mappedPath = mappedPath;
        this.service = service;
    }

    /**
     * Returns {@code true} if and only if {@link ServiceMapping} found a matching {@link Service}.
     */
    public boolean isPresent() {
        return mappedPath != null;
    }

    /**
     * Returns the path translated by the matching {@link PathMapping}.
     *
     * @throws IllegalStateException if there's no match
     */
    public String mappedPath() {
        ensurePresence();
        return mappedPath;
    }

    @SuppressWarnings("unchecked")
    private <T extends Service> T delegate() {
        ensurePresence();
        return (T) service;
    }

    private void ensurePresence() {
        if (!isPresent()) {
            throw new IllegalStateException("mapping unavailable");
        }
    }

    @Override
    public void serviceAdded(Server server) throws Exception {
        ServiceCallbackInvoker.invokeServiceAdded(server, delegate());
    }

    /**
     * Returns the matching {@link Service}'s {@link ServiceCodec}.
     *
     * @throws IllegalStateException if there's no match
     */
    @Override
    public ServiceCodec codec() {
        return delegate().codec();
    }

    /**
     * Returns the matching {@link Service}'s {@link ServiceInvocationHandler}.
     *
     * @throws IllegalStateException if there's no match
     */
    @Override
    public ServiceInvocationHandler handler() {
        return delegate().handler();
    }

    @Override
    public <T extends Service> Optional<T> as(Class<T> serviceType) {
        final Optional<T> result = Service.super.as(serviceType);
        return result.isPresent() ? result : delegate().as(serviceType);
    }

    @Override
    public String toString() {
        return isPresent() ? mappedPath() + " -> " + delegate() : "<empty>";
    }
}
