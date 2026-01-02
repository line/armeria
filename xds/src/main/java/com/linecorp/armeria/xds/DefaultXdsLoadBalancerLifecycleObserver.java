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

package com.linecorp.armeria.xds;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.client.endpoint.HostSet;
import com.linecorp.armeria.xds.client.endpoint.LoadBalancerState;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancerLifecycleObserver;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

final class DefaultXdsLoadBalancerLifecycleObserver implements XdsLoadBalancerLifecycleObserver {

    private final SnapshotUpdateRecorder resourceUpdatedRecorder;
    private final SnapshotUpdateRecorder endpointsUpdatedRecorder;
    private final SnapshotUpdateRecorder stateUpdatedRecorder;
    private final SnapshotUpdateRecorder stateRejectedRecorder;
    private final LoadBalancerRecorder loadBalancerRecorder;

    DefaultXdsLoadBalancerLifecycleObserver(MeterIdPrefix prefix,
                                            MeterRegistry meterRegistry, String clusterName) {
        prefix = prefix.withTags("cluster", clusterName);
        resourceUpdatedRecorder =
                new SnapshotUpdateRecorder(meterRegistry, prefix.append("lb.resource.updated"));
        endpointsUpdatedRecorder =
                new SnapshotUpdateRecorder(meterRegistry, prefix.append("lb.endpoints.updated"));
        stateUpdatedRecorder =
                new SnapshotUpdateRecorder(meterRegistry, prefix.append("lb.state.updated"));
        stateRejectedRecorder =
                new SnapshotUpdateRecorder(meterRegistry, prefix.append("lb.state.rejected"));
        loadBalancerRecorder = new LoadBalancerRecorder(meterRegistry, prefix);
    }

    @Override
    public void resourceUpdated(ClusterSnapshot snapshot) {
        resourceUpdatedRecorder.snapshotUpdated(snapshot);
    }

    @Override
    public void endpointsUpdated(ClusterSnapshot snapshot, List<Endpoint> endpoints) {
        endpointsUpdatedRecorder.snapshotUpdated(snapshot);
    }

    @Override
    public void stateUpdated(ClusterSnapshot snapshot, LoadBalancerState state) {
        stateUpdatedRecorder.snapshotUpdated(snapshot);
        loadBalancerRecorder.stateUpdated(state);
    }

    @Override
    public void stateRejected(ClusterSnapshot snapshot, List<Endpoint> endpoints, Throwable cause) {
        stateRejectedRecorder.snapshotUpdated(snapshot);
    }

    @Override
    public void close() {
        resourceUpdatedRecorder.close();
        endpointsUpdatedRecorder.close();
        stateUpdatedRecorder.close();
        stateRejectedRecorder.close();
        loadBalancerRecorder.close();
    }

    private static class LoadBalancerRecorder implements SafeCloseable {

        private final Gauge subsetsGauge;
        private Map<Integer, PriorityRecorder> priorityMap = new HashMap<>();
        private final MeterRegistry meterRegistry;
        private final MeterIdPrefix prefix;

        private volatile int numSubsets;

        LoadBalancerRecorder(MeterRegistry meterRegistry, MeterIdPrefix prefix) {
            this.meterRegistry = meterRegistry;
            this.prefix = prefix;
            subsetsGauge = Gauge.builder(prefix.name("lb.state.subsets"), () -> numSubsets)
                                .tags(prefix.tags())
                                .description("the number of subsets")
                                .register(meterRegistry);
        }

        void stateUpdated(LoadBalancerState state) {

            numSubsets = state.subsetStates().size();

            final Map<Integer, PriorityRecorder> prevPriorityMap = priorityMap;
            final Map<Integer, PriorityRecorder> priorityMap = new HashMap<>();

            // handle new HostSets
            for (Entry<Integer, HostSet> e: state.hostSets().entrySet()) {
                final int priority = e.getKey();
                PriorityRecorder recorder = prevPriorityMap.get(e.getKey());
                if (recorder == null) {
                    recorder = new PriorityRecorder(meterRegistry, prefix, priority);
                }
                recorder.update(priority, e.getValue(), state);
                priorityMap.put(priority, recorder);
            }

            // nullify missing priorities
            for (Entry<Integer, PriorityRecorder> e: prevPriorityMap.entrySet()) {
                if (!priorityMap.containsKey(e.getKey())) {
                    e.getValue().close();
                }
            }
            this.priorityMap = ImmutableMap.copyOf(priorityMap);
        }

        @Override
        public void close() {
            meterRegistry.remove(subsetsGauge);
            priorityMap.values().forEach(PriorityRecorder::close);
        }
    }

    private static class PriorityRecorder implements SafeCloseable {

        private Map<Locality, LocalityRecorder> localityMap = ImmutableMap.of();
        private final MeterRegistry meterRegistry;
        private final MeterIdPrefix prefix;
        private final int priority;
        private final List<Meter> meters;

        private volatile int healthyLoad;
        private volatile int degradedLoad;
        private volatile boolean panicState;
        private volatile double zarLocalPercentage;

        PriorityRecorder(MeterRegistry meterRegistry, MeterIdPrefix prefix, int priority) {
            this.meterRegistry = meterRegistry;
            this.priority = priority;
            prefix = prefix.withTags("priority", Integer.toString(priority));
            this.prefix = prefix;

            final ImmutableList.Builder<Meter> metersBuilder = ImmutableList.builder();
            metersBuilder.add(Gauge.builder(prefix.name("lb.state.load.healthy"), () -> healthyLoad)
                                   .tags(prefix.tags())
                                   .register(meterRegistry));
            metersBuilder.add(Gauge.builder(prefix.name("lb.state.load.degraded"), () -> degradedLoad)
                                   .tags(prefix.tags())
                                   .register(meterRegistry));
            metersBuilder.add(Gauge.builder(prefix.name("lb.state.panic"), () -> panicState ? 1 : 0)
                                   .tags(prefix.tags())
                                   .description("1: panic, 0: normal")
                                   .register(meterRegistry));
            if (priority == 0) {
                metersBuilder.add(Gauge.builder(prefix.name("lb.zar.local.percentage"),
                                                () -> zarLocalPercentage)
                                       .tags(prefix.tags())
                                       .register(meterRegistry));
            }
            meters = metersBuilder.build();
        }

        void update(int priority, HostSet hostSet, LoadBalancerState state) {
            assert this.priority == priority;

            healthyLoad = state.healthyPriorityLoad().getOrDefault(priority, 0);
            degradedLoad = state.degradedPriorityLoad().getOrDefault(priority, 0);
            panicState = state.perPriorityPanic().getOrDefault(priority, false);
            zarLocalPercentage = state.zarLocalPercentage();

            // update locality-specific metrics
            final Map<Locality, LocalityRecorder> prevLocalityMap = localityMap;
            final Map<Locality, LocalityRecorder> localityMap = new HashMap<>();
            for (Locality locality: hostSet.endpointGroupPerLocality().keySet()) {
                LocalityRecorder recorder = prevLocalityMap.get(locality);
                if (recorder == null) {
                    recorder = new LocalityRecorder(meterRegistry, prefix, locality, priority);
                }
                recorder.update(hostSet, state.zarResidualPercentages().getOrDefault(locality, 0d));
                localityMap.put(locality, recorder);
            }
            for (Entry<Locality, LocalityRecorder> e: prevLocalityMap.entrySet()) {
                if (!localityMap.containsKey(e.getKey())) {
                    e.getValue().close();
                }
            }
            this.localityMap = ImmutableMap.copyOf(localityMap);
        }

        @Override
        public void close() {
            meters.forEach(meterRegistry::remove);
            localityMap.values().forEach(LocalityRecorder::close);
        }
    }

    private static class LocalityRecorder implements SafeCloseable {

        private final MeterRegistry meterRegistry;
        private final Locality locality;
        private final List<Meter> meters;

        private volatile int total;
        private volatile int healthy;
        private volatile int degraded;
        private volatile int localityWeight;
        private volatile double zarPercentage;

        LocalityRecorder(MeterRegistry meterRegistry, MeterIdPrefix prefix, Locality locality,
                         int priority) {
            this.meterRegistry = meterRegistry;
            this.locality = locality;
            prefix = prefix.withTags("region", locality.getRegion(),
                                     "zone", locality.getZone(),
                                     "sub_zone", locality.getSubZone());
            final ImmutableList.Builder<Meter> metersBuilder = ImmutableList.builder();
            metersBuilder.add(Gauge.builder(prefix.name("lb.membership.total"), () -> total)
                                   .tags(prefix.tags())
                                   .register(meterRegistry));
            metersBuilder.add(Gauge.builder(prefix.name("lb.membership.healthy"), () -> healthy)
                                   .tags(prefix.tags())
                                   .register(meterRegistry));
            metersBuilder.add(Gauge.builder(prefix.name("lb.membership.degraded"), () -> degraded)
                                   .tags(prefix.tags())
                                   .register(meterRegistry));
            metersBuilder.add(Gauge.builder(prefix.name("lb.locality.weight"), () -> localityWeight)
                                   .tags(prefix.tags())
                                   .register(meterRegistry));
            if (priority == 0) {
                metersBuilder.add(Gauge.builder(prefix.name("lb.zar.residual.percentage"), () -> zarPercentage)
                                       .tags(prefix.tags())
                                       .register(meterRegistry));
            }
            meters = metersBuilder.build();
        }

        void update(HostSet hostSet, double zarPercentage) {
            final EndpointGroup allHosts = hostSet.endpointGroupPerLocality()
                                                  .getOrDefault(locality, EndpointGroup.of());
            total = allHosts.endpoints().size();
            final EndpointGroup healthyHosts = hostSet.healthyEndpointGroupPerLocality()
                                                      .getOrDefault(locality, EndpointGroup.of());
            healthy = healthyHosts.endpoints().size();
            final EndpointGroup degradedHosts = hostSet.degradedEndpointGroupPerLocality()
                                                       .getOrDefault(locality, EndpointGroup.of());
            degraded = degradedHosts.endpoints().size();
            localityWeight = hostSet.localityWeights().getOrDefault(locality, 0);
            this.zarPercentage = zarPercentage;
        }

        @Override
        public void close() {
            meters.forEach(meterRegistry::remove);
        }
    }

    private static class SnapshotUpdateRecorder implements SafeCloseable {

        private final MeterRegistry meterRegistry;
        private volatile long revision;
        private final Gauge revisionGauge;
        private final Counter updatedCounter;

        SnapshotUpdateRecorder(MeterRegistry meterRegistry, MeterIdPrefix prefix) {
            this.meterRegistry = meterRegistry;
            revisionGauge = Gauge.builder(prefix.name("revision"), () -> revision)
                                 .tags(prefix.tags())
                                 .register(meterRegistry);
            updatedCounter = Counter.builder(prefix.name("count"))
                                    .tags(prefix.tags())
                                    .register(meterRegistry);
        }

        void snapshotUpdated(ClusterSnapshot snapshot) {
            revision = snapshot.xdsResource().revision();
            updatedCounter.increment();
        }

        @Override
        public void close() {
            meterRegistry.remove(revisionGauge);
            meterRegistry.remove(updatedCounter);
        }
    }
}
