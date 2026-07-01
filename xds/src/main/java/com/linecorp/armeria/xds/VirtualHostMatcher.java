/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.annotation.Nullable;

final class VirtualHostMatcher {

    private final Map<String, VirtualHostSnapshot> exactMatch;
    // This can be optimized by keeping an additional map of each length
    // To reduce complexity, for now just iterate and match
    private final List<Entry<String, VirtualHostSnapshot>> prefixMatch;
    private final List<Entry<String, VirtualHostSnapshot>> suffixMatch;
    @Nullable
    private final VirtualHostSnapshot defaultVirtualHost;

    private final boolean ignorePortInHostMatching;

    VirtualHostMatcher(List<VirtualHostSnapshot> virtualHostSnapshots,
                       boolean ignorePortInHostMatching) {
        final Map<String, VirtualHostSnapshot> exactMatch = new HashMap<>();
        final List<Entry<String, VirtualHostSnapshot>> prefixMatch = new ArrayList<>();
        final List<Entry<String, VirtualHostSnapshot>> suffixMatch = new ArrayList<>();
        VirtualHostSnapshot defaultVirtualHost = null;

        for (VirtualHostSnapshot virtualHostSnapshot: virtualHostSnapshots) {
            for (String domain: virtualHostSnapshot.xdsResource().resource().getDomainsList()) {
                domain = Ascii.toLowerCase(domain);
                if ("*".equals(domain)) {
                    if (defaultVirtualHost == null) {
                        defaultVirtualHost = virtualHostSnapshot;
                    }
                    continue;
                }
                if (domain.startsWith("*")) {
                    suffixMatch.add(new SimpleEntry<>(domain.substring(1), virtualHostSnapshot));
                    continue;
                }
                if (domain.endsWith("*")) {
                    prefixMatch.add(new SimpleEntry<>(domain.substring(0, domain.length() - 1),
                                                      virtualHostSnapshot));
                    continue;
                }
                if (!exactMatch.containsKey(domain)) {
                    exactMatch.put(domain, virtualHostSnapshot);
                }
            }
        }
        // The longest domain should match the host
        prefixMatch.sort(Comparator.comparing(e -> e.getKey().length(), Comparator.reverseOrder()));
        suffixMatch.sort(Comparator.comparing(e -> e.getKey().length(), Comparator.reverseOrder()));

        this.exactMatch = Collections.unmodifiableMap(exactMatch);
        this.suffixMatch = Collections.unmodifiableList(suffixMatch);
        this.prefixMatch = Collections.unmodifiableList(prefixMatch);
        this.defaultVirtualHost = defaultVirtualHost;
        this.ignorePortInHostMatching = ignorePortInHostMatching;
    }

    @Nullable
    VirtualHostSnapshot find(@Nullable String authority) {
        if (exactMatch.isEmpty() && prefixMatch.isEmpty() && suffixMatch.isEmpty()) {
            return defaultVirtualHost;
        }
        if (authority == null) {
            return defaultVirtualHost;
        }
        if (ignorePortInHostMatching) {
            final int colonIdx = authority.lastIndexOf(':');
            final int v6EndIdx = authority.lastIndexOf(']');
            if (colonIdx != -1 && colonIdx > v6EndIdx) {
                // An ipv6 address in the host header must be enclosed in square brackets
                // https://datatracker.ietf.org/doc/html/rfc3986#section-3.2.2
                authority = authority.substring(0, colonIdx);
            }
        }
        final VirtualHostSnapshot virtualHostSnapshot = exactMatch.get(authority);
        if (virtualHostSnapshot != null) {
            return virtualHostSnapshot;
        }
        for (Entry<String, VirtualHostSnapshot> entry: suffixMatch) {
            if (authority.length() <= entry.getKey().length()) {
                continue;
            }
            if (authority.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        for (Entry<String, VirtualHostSnapshot> entry: prefixMatch) {
            if (authority.length() <= entry.getKey().length()) {
                continue;
            }
            if (authority.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return defaultVirtualHost;
    }
}
