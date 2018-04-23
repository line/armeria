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

import com.linecorp.armeria.client.Endpoint;

/**
 * Builds a new {@link DnsServiceEndpointGroup} that sources its {@link Endpoint} list from the {@code SRV}
 * DNS records of a certain hostname.
 */
public final class DnsServiceEndpointGroupBuilder
        extends DnsEndpointGroupBuilder<DnsServiceEndpointGroupBuilder> {

    /**
     * Creates a new instance that builds a {@link DnsServiceEndpointGroup} for the specified {@code hostname}.
     *
     * @param hostname the hostname to query DNS queries for
     */
    public DnsServiceEndpointGroupBuilder(String hostname) {
        super(hostname);
    }

    /**
     * Returns a newly created {@link DnsServiceEndpointGroup}.
     */
    public DnsServiceEndpointGroup build() {
        return new DnsServiceEndpointGroup(eventLoop(), minTtl(), maxTtl(),
                                           serverAddressStreamProvider(),
                                           backoff(), hostname());
    }
}
