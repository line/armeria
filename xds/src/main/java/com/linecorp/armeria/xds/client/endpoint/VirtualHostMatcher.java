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

package com.linecorp.armeria.xds.client.endpoint;

import com.google.common.base.Ascii;

import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.VirtualHostSnapshot;

import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;

final class VirtualHostMatcher {

    private final boolean ignorePortInHostMatching;
    private final Mapping<String, VirtualHostEntry> mapping;

    VirtualHostMatcher(ListenerSnapshot listenerSnapshot) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            ignorePortInHostMatching = false;
            mapping = input -> VirtualHostEntry.NOOP;
            return;
        }

        final DomainWildcardMappingBuilder<VirtualHostEntry> mappingBuilder =
                new DomainWildcardMappingBuilder<>(VirtualHostEntry.NOOP);
        for (VirtualHostSnapshot virtualHostSnapshot: routeSnapshot.virtualHostSnapshots()) {
            for (String domain: virtualHostSnapshot.xdsResource().resource().getDomainsList()) {
                domain = Ascii.toLowerCase(domain);
                mappingBuilder.add(domain, new VirtualHostEntry(virtualHostSnapshot));
            }
        }
        mapping = mappingBuilder.build();
        ignorePortInHostMatching = routeSnapshot.xdsResource().resource().getIgnorePortInHostMatching();
    }

    @Nullable
    VirtualHostSnapshot find(PreClientRequestContext ctx) {
        String authority = ctx.authority();
        if (authority != null && ignorePortInHostMatching) {
            final int colonIdx = authority.lastIndexOf(':');
            final int v6EndIdx = authority.lastIndexOf(']');
            if (colonIdx != -1 && colonIdx > v6EndIdx) {
                // An ipv6 address in the host header must be enclosed in square brackets
                // https://datatracker.ietf.org/doc/html/rfc3986#section-3.2.2
                authority = authority.substring(0, colonIdx);
            }
        }
        return mapping.map(authority).get();
    }

    static class VirtualHostEntry {

        private static final VirtualHostEntry NOOP = new VirtualHostEntry();

        @Nullable
        private final VirtualHostSnapshot virtualHostSnapshot;

        VirtualHostEntry(VirtualHostSnapshot virtualHostSnapshot) {
            this.virtualHostSnapshot = virtualHostSnapshot;
        }

        private VirtualHostEntry() {
            virtualHostSnapshot = null;
        }

        @Nullable
        VirtualHostSnapshot get() {
            return virtualHostSnapshot;
        }
    }
}
