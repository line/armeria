/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;

import io.envoyproxy.envoy.config.core.v3.Locality;

final class EndpointGroupUtil {

    static Map<Locality, List<Endpoint>> endpointsByLocality(List<Endpoint> endpoints) {
        return endpoints.stream().collect(Collectors.groupingBy(EndpointUtil::locality));
    }

    static EndpointGroup filter(List<Endpoint> endpoints, EndpointSelectionStrategy strategy,
                                Predicate<Endpoint> predicate) {
        final List<Endpoint> filteredEndpoints =
                endpoints.stream().filter(predicate).collect(Collectors.toList());
        return EndpointGroup.of(strategy, filteredEndpoints);
    }

    static EndpointGroup filter(EndpointGroup origEndpointGroup, Predicate<Endpoint> predicate) {
        return filter(origEndpointGroup.endpoints(), origEndpointGroup.selectionStrategy(), predicate);
    }

    static Map<Locality, EndpointGroup> filterByLocality(Map<Locality, List<Endpoint>> endpointsMap,
                                                         EndpointSelectionStrategy strategy,
                                                         Predicate<Endpoint> predicate) {
        final ImmutableMap.Builder<Locality, EndpointGroup> filteredLocality = ImmutableMap.builder();
        for (Entry<Locality, List<Endpoint>> entry: endpointsMap.entrySet()) {
            final EndpointGroup endpointGroup = filter(entry.getValue(), strategy, predicate);
            if (endpointGroup.endpoints().isEmpty()) {
                continue;
            }
            filteredLocality.put(entry.getKey(), endpointGroup);
        }
        return filteredLocality.build();
    }

    static Map<Locality, EndpointGroup> filterByLocality(Map<Locality, EndpointGroup> origLocality,
                                                         Predicate<Endpoint> predicate) {
        final ImmutableMap.Builder<Locality, EndpointGroup> filteredLocality = ImmutableMap.builder();
        for (Entry<Locality, EndpointGroup> entry: origLocality.entrySet()) {
            final EndpointGroup endpointGroup = filter(entry.getValue(), predicate);
            if (endpointGroup.endpoints().isEmpty()) {
                continue;
            }
            filteredLocality.put(entry.getKey(), endpointGroup);
        }
        return filteredLocality.build();
    }

    private EndpointGroupUtil() {}
}
