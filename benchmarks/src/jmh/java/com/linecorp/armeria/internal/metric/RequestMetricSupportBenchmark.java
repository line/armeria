/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.metric;

import static org.mockito.Mockito.mock;

import org.openjdk.jmh.annotations.Benchmark;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;

public class RequestMetricSupportBenchmark {

    private static final MeterIdPrefixFunction PREFIX_FUNC = MeterIdPrefixFunction.ofDefault("benchmark");
    private static final DefaultRequestLog REQUEST_LOG = new DefaultRequestLog(
            ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/")));

    static {
        REQUEST_LOG.startRequest(mock(Channel.class), SessionProtocol.H2C);
        REQUEST_LOG.requestContent(RpcRequest.of(RequestMetricSupportBenchmark.class, "benchmark"), null);
    }

    @Benchmark
    public String registerSameTags() {
        final MeterIdPrefix prefix = PREFIX_FUNC.apply(NoopMeterRegistry.get(), REQUEST_LOG);
        // Normally we would be memoizing an object with actual metrics but for this benchmark it doesn't matter
        // what object we use, we just want to check the performance of creating a prefix and registering.
        return MicrometerUtil.register(NoopMeterRegistry.get(), prefix,
                                       String.class,
                                       (u1, u2) -> "foo");
    }
}
