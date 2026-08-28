/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

public final class KeepAliveHandlerUtil {

    private static final long MIN_MAX_CONNECTION_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final AttributeKey<ConnectionLifespan> CONNECTION_LIFESPAN =
            AttributeKey.valueOf(KeepAliveHandlerUtil.class, "connectionLifespan");

    public static boolean needsKeepAliveHandler(long idleTimeoutMillis, long pingIntervalMillis,
                                                long maxConnectionAgeMillis, int maxNumRequestsPerConnection) {
        return idleTimeoutMillis > 0 || pingIntervalMillis > 0 ||
               maxConnectionAgeMillis > 0 || maxNumRequestsPerConnection > 0;
    }

    /**
     * Samples the effective maximum age of the connection and schedules a pre-protocol close task.
     * The keep-alive handler takes ownership of the remaining lifespan after protocol detection, while
     * closing the channel before detection cancels and cleans up the task.
     */
    public static void initializeConnectionLifespan(Channel channel, long maxConnectionAgeMillis,
                                                    double maxConnectionAgeJitterRate) {
        if (maxConnectionAgeMillis <= 0) {
            return;
        }

        final long maxConnectionAgeNanos = TimeUnit.MILLISECONDS.toNanos(maxConnectionAgeMillis);
        final double randomValue = maxConnectionAgeJitterRate == 0 ? 0 :
                                   ThreadLocalRandom.current().nextDouble();
        final long effectiveMaxConnectionAgeNanos = jitteredMaxConnectionAgeNanos(
                maxConnectionAgeNanos, maxConnectionAgeJitterRate, randomValue);
        final long connectionStartTimeNanos = System.nanoTime();
        final ScheduledFuture<?> preProtocolMaxAgeFuture = channel.eventLoop().schedule(
                (Runnable) channel::close, effectiveMaxConnectionAgeNanos, TimeUnit.NANOSECONDS);
        final ConnectionLifespan connectionLifespan =
                new ConnectionLifespan(connectionStartTimeNanos, effectiveMaxConnectionAgeNanos,
                                       preProtocolMaxAgeFuture);
        final Attribute<ConnectionLifespan> connectionLifespanAttr = channel.attr(CONNECTION_LIFESPAN);
        if (!connectionLifespanAttr.compareAndSet(null, connectionLifespan)) {
            connectionLifespan.cancelPreProtocolMaxAgeFuture();
            return;
        }
        channel.closeFuture().addListener(unused -> {
            connectionLifespan.cancelPreProtocolMaxAgeFuture();
            connectionLifespanAttr.compareAndSet(connectionLifespan, null);
        });
    }

    @Nullable
    static ConnectionLifespan connectionLifespan(Channel channel) {
        return channel.attr(CONNECTION_LIFESPAN).get();
    }

    @VisibleForTesting
    static long jitteredMaxConnectionAgeNanos(long maxConnectionAgeNanos,
                                              double jitterRate, double randomValue) {
        if (maxConnectionAgeNanos <= 0 || jitterRate == 0) {
            return maxConnectionAgeNanos;
        }

        final long jitteredLowerBound = (long) (maxConnectionAgeNanos * (1 - jitterRate));
        final long lowerBound = Math.min(maxConnectionAgeNanos,
                                         Math.max(MIN_MAX_CONNECTION_AGE_NANOS, jitteredLowerBound));
        return lowerBound + (long) ((maxConnectionAgeNanos - lowerBound) * randomValue);
    }

    static final class ConnectionLifespan {

        private final long connectionStartTimeNanos;
        private final long effectiveMaxConnectionAgeNanos;
        @Nullable
        private ScheduledFuture<?> preProtocolMaxAgeFuture;

        ConnectionLifespan(long connectionStartTimeNanos, long effectiveMaxConnectionAgeNanos,
                           ScheduledFuture<?> preProtocolMaxAgeFuture) {
            this.connectionStartTimeNanos = connectionStartTimeNanos;
            this.effectiveMaxConnectionAgeNanos = effectiveMaxConnectionAgeNanos;
            this.preProtocolMaxAgeFuture = preProtocolMaxAgeFuture;
        }

        long connectionStartTimeNanos() {
            return connectionStartTimeNanos;
        }

        long effectiveMaxConnectionAgeNanos() {
            return effectiveMaxConnectionAgeNanos;
        }

        void protocolDetected() {
            cancelPreProtocolMaxAgeFuture();
        }

        private void cancelPreProtocolMaxAgeFuture() {
            if (preProtocolMaxAgeFuture != null) {
                preProtocolMaxAgeFuture.cancel(false);
                preProtocolMaxAgeFuture = null;
            }
        }
    }

    private KeepAliveHandlerUtil() {}
}
