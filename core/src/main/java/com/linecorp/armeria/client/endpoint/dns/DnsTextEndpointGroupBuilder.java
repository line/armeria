/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.dns;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.Endpoint;

/**
 * Builds a new {@link DnsTextEndpointGroup} that sources its {@link Endpoint} list from the {@code TXT}
 * DNS records of a certain hostname.
 */
public final class DnsTextEndpointGroupBuilder
        extends DnsEndpointGroupBuilder<DnsTextEndpointGroupBuilder> {

    private final Function<byte[], Endpoint> mapping;

    /**
     * Creates a new instance that builds a {@link DnsTextEndpointGroup} for the specified {@code hostname}.
     *
     * @param hostname the hostname to query DNS queries for
     * @param mapping the {@link Function} that maps the content of a {@code TXT} record into
     *                an {@link Endpoint}. The {@link Function} is expected to return {@code null}
     *                if the record contains unsupported content.
     */
    public DnsTextEndpointGroupBuilder(String hostname, Function<byte[], Endpoint> mapping) {
        super(hostname);
        this.mapping = requireNonNull(mapping, "mapping");
    }

    /**
     * Returns a newly created {@link DnsTextEndpointGroup}.
     */
    public DnsTextEndpointGroup build() {
        return new DnsTextEndpointGroup(eventLoop(), minTtl(), maxTtl(),
                                        serverAddressStreamProvider(),
                                        backoff(), hostname(), mapping);
    }
}
