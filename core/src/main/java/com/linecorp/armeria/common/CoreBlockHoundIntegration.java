/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.ResourceBundle;

import com.linecorp.armeria.common.annotation.UnstableApi;

import reactor.blockhound.BlockHound.Builder;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * A {@link BlockHoundIntegration} for the core module.
 */
@UnstableApi
public final class CoreBlockHoundIntegration implements BlockHoundIntegration {
    @Override
    public void applyTo(Builder builder) {
        // short locks
        builder.allowBlockingCallsInside("com.linecorp.armeria.client.HttpClientFactory",
                                         "pool");
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.common.util.ReentrantShortLock",
                                         "lock");

        // Thread.yield can be eventually called when PooledObjects.copyAndClose is called
        builder.allowBlockingCallsInside("io.netty.util.internal.ReferenceCountUpdater", "release");

        // hdr histogram holds locks
        builder.allowBlockingCallsInside("org.HdrHistogram.ConcurrentHistogram", "getCountAtIndex");
        builder.allowBlockingCallsInside("org.HdrHistogram.WriterReaderPhaser", "flipPhase");

        // StreamMessageInputStream internally uses a blocking queue
        // ThreadPoolExecutor.execute internally uses a blocking queue
        builder.allowBlockingCallsInside("java.util.concurrent.LinkedBlockingQueue", "offer");

        // a single blocking call is incurred for the first invocation, but the result is cached.
        builder.allowBlockingCallsInside("com.linecorp.armeria.internal.client.PublicSuffix",
                                         "get");
        builder.allowBlockingCallsInside("java.util.ServiceLoader$LazyClassPathLookupIterator",
                                         "parse");
        builder.allowBlockingCallsInside(ResourceBundle.class.getName(), "getBundle");
        builder.allowBlockingCallsInside("io.netty.handler.codec.compression.Brotli", "<clinit>");

        // a lock is held temporarily when adding workers
        builder.allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "addWorker");

        // prometheus exporting holds a lock temporarily
        builder.allowBlockingCallsInside(
                "com.linecorp.armeria.server.metric.PrometheusExpositionService", "doGet");

        // Thread.yield can be called
        builder.allowBlockingCallsInside(
                "java.util.concurrent.FutureTask", "handlePossibleCancellationInterrupt");
    }
}
