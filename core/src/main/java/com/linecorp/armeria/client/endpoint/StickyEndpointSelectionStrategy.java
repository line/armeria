/*
 * Copyright 2017 LINE Corporation
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
import java.util.function.ToLongFunction;

import com.google.common.hash.Hashing;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpRequest;

/**
 * An {@link EndpointSelector} strategy which implements sticky load-balancing using
 * user passed {@link ToLongFunction} to compute hashes for consistent hashing.
 *
 * <p>This strategy can be useful when all requests that qualify some given criterias must be sent to the same
 * backend server. A common use case is to send all requests for the same logged-in user to the same backend,
 * which could have a local cache keyed by user id.
 *
 * <p>In below example, created strategy will route all {@link HttpRequest} which have the same value for key
 * "cookie" of its header to the same server:
 *
 * <pre>{@code
 * ToLongFunction<ClientRequestContext> hasher = (ClientRequestContext ctx) -> {
 *     return ((HttpRequest) ctx.request()).headers().get(AsciiString.of("cookie")).hashCode();
 * };
 * final StickyEndpointSelectionStrategy strategy = new StickyEndpointSelectionStrategy(hasher);
 * }</pre>
 */
public final class StickyEndpointSelectionStrategy implements EndpointSelectionStrategy {

    private final ToLongFunction<ClientRequestContext> requestContextHasher;

    /**
     * Creates a new {@link StickyEndpointSelectionStrategy}
     * with provided hash function to hash a {@link ClientRequestContext} to a {@code long}.
     *
     * @param requestContextHasher The default {@link ToLongFunction} of {@link ClientRequestContext}
     */
    public StickyEndpointSelectionStrategy(ToLongFunction<ClientRequestContext> requestContextHasher) {
        this.requestContextHasher = requireNonNull(requestContextHasher, "requestContextHasher");
    }

    /**
     * Creates a new {@link StickyEndpointSelector}.
     *
     * @param endpointGroup an {@link EndpointGroup}
     * @return a new {@link StickyEndpointSelector}
     */
    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new StickyEndpointSelector(requestContextHasher, endpointGroup);
    }

    private final class StickyEndpointSelector implements EndpointSelector {

        private final ToLongFunction<ClientRequestContext> requestContextHasher;
        private final EndpointGroup endpointGroup;

        StickyEndpointSelector(ToLongFunction<ClientRequestContext> requestContextHasher,
                               EndpointGroup endpointGroup) {
            this.requestContextHasher = requireNonNull(requestContextHasher, "requestContextHasher");
            this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        }

        @Override
        public EndpointGroup group() {
            return endpointGroup;
        }

        @Override
        public EndpointSelectionStrategy strategy() {
            return StickyEndpointSelectionStrategy.this;
        }

        @Override
        public Endpoint select(ClientRequestContext ctx) {

            final List<Endpoint> endpoints = endpointGroup.endpoints();
            if (endpoints.isEmpty()) {
                throw new EndpointGroupException(endpointGroup + " is empty");
            }

            long key = requestContextHasher.applyAsLong(ctx);
            int nearest = Hashing.consistentHash(key, endpoints.size());
            return endpoints.get(nearest);
        }
    }
}
