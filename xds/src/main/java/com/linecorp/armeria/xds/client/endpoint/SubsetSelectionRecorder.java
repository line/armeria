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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.EndpointSnapshot;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class SubsetSelectionRecorder {

    private static final String FALLBACK_SUBSET = "_fallback_";

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix prefix;
    private final Counter noMatchCounter;

    SubsetSelectionRecorder(MeterRegistry meterRegistry, MeterIdPrefix prefix, String clusterName) {
        this.meterRegistry = meterRegistry;
        this.prefix = prefix.withTags("cluster", clusterName);
        noMatchCounter = Counter.builder(this.prefix.name("lb.select.subset"))
                                .tags(this.prefix.tags())
                                .tag("result", "miss")
                                .tag("subset", "_no_match_")
                                .register(meterRegistry);
    }

    XdsLoadBalancer wrap(XdsLoadBalancer delegate, Struct subsetMetadata) {
        final String subsetValue = serializeStruct(subsetMetadata);
        return createRecordingLoadBalancer(delegate, subsetValue);
    }

    XdsLoadBalancer wrapFallback(XdsLoadBalancer delegate) {
        return createRecordingLoadBalancer(delegate, FALLBACK_SUBSET);
    }

    void recordNoMatch() {
        noMatchCounter.increment();
    }

    private XdsLoadBalancer createRecordingLoadBalancer(XdsLoadBalancer delegate, String subsetValue) {
        final Counter hitCounter = Counter.builder(prefix.name("lb.select.subset"))
                                              .tags(prefix.tags())
                                              .tag("result", "hit")
                                              .tag("subset", subsetValue)
                                              .register(meterRegistry);
        final Counter missCounter = Counter.builder(prefix.name("lb.select.subset"))
                                           .tags(prefix.tags())
                                           .tag("result", "miss")
                                           .tag("subset", subsetValue)
                                           .register(meterRegistry);
        return new RecordingLoadBalancer(delegate, hitCounter, missCounter);
    }

    private static String serializeStruct(Struct struct) {
        return struct.getFieldsMap().entrySet().stream()
                     .sorted(Map.Entry.comparingByKey())
                     .map(e -> e.getKey() + '=' + valueToString(e.getValue()))
                     .collect(Collectors.joining(","));
    }

    private static String valueToString(Value value) {
        switch (value.getKindCase()) {
            case STRING_VALUE:
                return value.getStringValue();
            case NUMBER_VALUE:
                return Double.toString(value.getNumberValue());
            case BOOL_VALUE:
                return Boolean.toString(value.getBoolValue());
            default:
                return value.toString().trim();
        }
    }

    private static final class RecordingLoadBalancer implements XdsLoadBalancer {

        private final XdsLoadBalancer delegate;
        private final Counter hitCounter;
        private final Counter missCounter;

        RecordingLoadBalancer(XdsLoadBalancer delegate, Counter hitCounter, Counter missCounter) {
            this.delegate = delegate;
            this.hitCounter = hitCounter;
            this.missCounter = missCounter;
        }

        @Nullable
        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            final Endpoint endpoint = delegate.selectNow(ctx);
            (endpoint != null ? hitCounter : missCounter).increment();
            return endpoint;
        }

        @Override
        public Map<Integer, HostSet> hostSets() {
            return delegate.hostSets();
        }

        @Override
        public Map<Integer, Boolean> perPriorityPanic() {
            return delegate.perPriorityPanic();
        }

        @Override
        public Map<Integer, Integer> healthyPriorityLoad() {
            return delegate.healthyPriorityLoad();
        }

        @Override
        public Map<Integer, Integer> degradedPriorityLoad() {
            return delegate.degradedPriorityLoad();
        }

        @Override
        public Map<Locality, Double> zarResidualPercentages() {
            return delegate.zarResidualPercentages();
        }

        @Override
        public double zarLocalPercentage() {
            return delegate.zarLocalPercentage();
        }

        @Override
        public Map<Struct, LoadBalancerState> subsetStates() {
            return delegate.subsetStates();
        }

        @Override
        public List<Endpoint> allEndpoints() {
            return delegate.allEndpoints();
        }

        @Override
        public EndpointSnapshot endpointSnapshot() {
            return delegate.endpointSnapshot();
        }

        @Nullable
        @Override
        public LoadBalancerState localLoadBalancer() {
            return delegate.localLoadBalancer();
        }
    }
}
