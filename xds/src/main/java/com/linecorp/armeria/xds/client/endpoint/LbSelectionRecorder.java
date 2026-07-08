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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class LbSelectionRecorder {

    static final String RESULT_HIT = "hit";
    static final String RESULT_MISS = "miss";
    static final String RESULT_NO_ENDPOINTS = "no_endpoints";
    static final String RESULT_NO_HOST_SOURCE = "no_host_source";

    private final MeterIdPrefix prefix;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<RequestKey, Counter> requestCounters = new ConcurrentHashMap<>();

    LbSelectionRecorder(MeterRegistry meterRegistry, MeterIdPrefix prefix, String clusterName) {
        this.meterRegistry = meterRegistry;
        this.prefix = prefix.withTags("cluster", clusterName);
    }

    void record(int priority, @Nullable Locality locality, @Nullable Endpoint endpoint) {
        record(priority, locality, endpoint != null ? RESULT_HIT : RESULT_MISS);
    }

    void record(int priority, @Nullable Locality locality, String result) {
        requestCounters.computeIfAbsent(RequestKey.of(priority, locality, result),
                                        this::createRequestCounter)
                       .increment();
    }

    private Counter createRequestCounter(RequestKey key) {
        final String region;
        final String zone;
        final String subZone;
        if (key.locality != null) {
            region = key.locality.getRegion();
            zone = key.locality.getZone();
            subZone = key.locality.getSubZone();
        } else {
            region = "";
            zone = "";
            subZone = "";
        }
        return Counter.builder(prefix.name("lb.select"))
                      .tags(prefix.tags())
                      .tag("priority", Integer.toString(key.priority))
                      .tag("region", region)
                      .tag("result", key.result)
                      .tag("sub.zone", subZone)
                      .tag("zone", zone)
                      .register(meterRegistry);
    }

    private static final class RequestKey {
        private static final RequestKey PRIORITY_0_HIT = new RequestKey(0, null, RESULT_HIT);
        private static final RequestKey PRIORITY_0_MISS = new RequestKey(0, null, RESULT_MISS);

        final int priority;
        @Nullable
        final Locality locality;
        final String result;

        static RequestKey of(int priority, @Nullable Locality locality, String result) {
            if (priority == 0 && locality == null) {
                if (RESULT_HIT.equals(result)) {
                    return PRIORITY_0_HIT;
                }
                if (RESULT_MISS.equals(result)) {
                    return PRIORITY_0_MISS;
                }
            }
            return new RequestKey(priority, locality, result);
        }

        private RequestKey(int priority, @Nullable Locality locality, String result) {
            this.priority = priority;
            this.locality = locality;
            this.result = result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RequestKey)) {
                return false;
            }
            final RequestKey that = (RequestKey) o;
            return priority == that.priority &&
                   Objects.equals(locality, that.locality) &&
                   result.equals(that.result);
        }

        @Override
        public int hashCode() {
            return Objects.hash(priority, locality, result);
        }
    }
}
