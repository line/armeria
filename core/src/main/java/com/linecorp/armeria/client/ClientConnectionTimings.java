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

package com.linecorp.armeria.client;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.Request;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * A holder class which has the start time and duration information about acquiring a connection
 * before a client sends a {@link Request}.
 */
public final class ClientConnectionTimings {

    public static final AttributeKey<ClientConnectionTimings> TIMINGS =
            AttributeKey.valueOf(ClientConnectionTimings.class, "TIMINGS");

    private final long acquiringConnectionStartMicros;
    private final long acquiringConnectionDurationNanos;

    private final long dnsResolutionDurationNanos;
    private final long socketConnectDurationNanos;
    private final long pendingAcquisitionDurationNanos;

    ClientConnectionTimings(long acquiringConnectionStartMicros, long acquiringConnectionDurationNanos,
                            long dnsResolutionDurationNanos, long socketConnectDurationNanos,
                            long pendingAcquisitionDurationNanos) {
        this.acquiringConnectionStartMicros = acquiringConnectionStartMicros;
        this.acquiringConnectionDurationNanos = acquiringConnectionDurationNanos;
        this.dnsResolutionDurationNanos = dnsResolutionDurationNanos;
        this.socketConnectDurationNanos = socketConnectDurationNanos;
        this.pendingAcquisitionDurationNanos = pendingAcquisitionDurationNanos;
    }

    /**
     * Returns the time when acquiring a connection started, in micros since the epoch.
     */
    public long acquiringConnectionStartMicros() {
        return acquiringConnectionStartMicros;
    }

    /**
     * Returns the duration which was taken to get a connection, in nanoseconds. This value is greater than or
     * equal to the sum of {@link #dnsResolutionDurationNanos()}, {@link #socketConnectDurationNanos()} and
     * {@link #pendingAcquisitionDurationNanos()}.
     */
    public long acquiringConnectionDurationNanos() {
        return acquiringConnectionDurationNanos;
    }

    /**
     * Returns the duration which was taken to resolve a DNS address, in nanoseconds.
     *
     * @return the duration, or {@code -1} if there was no action to resolve DNS address.
     */
    public long dnsResolutionDurationNanos() {
        return dnsResolutionDurationNanos;
    }

    /**
     * Returns the duration which was taken to connect a {@link Channel} to the remote peer, in nanoseconds.
     *
     * @return the duration, or {@code -1} if there was no action to connect to the remote peer.
     */
    public long socketConnectDurationNanos() {
        return socketConnectDurationNanos;
    }

    /**
     * Returns the duration which was taken to get a pending connection, in nanoseconds. This applies
     * only for HTTP/2.
     *
     * @return the duration, or {@code -1} if there was no action to get a pending connection.
     */
    public long pendingAcquisitionDurationNanos() {
        return pendingAcquisitionDurationNanos;
    }

    @Override
    public String toString() {
        final ToStringHelper toStringHelper =
                MoreObjects.toStringHelper(this)
                           .add("acquiringConnectionStartMicros", acquiringConnectionStartMicros)
                           .add("acquiringConnectionDurationNanos", acquiringConnectionDurationNanos);
        if (dnsResolutionDurationNanos != -1) {
            toStringHelper.add("dnsResolutionDurationNanos", dnsResolutionDurationNanos);
        }
        if (socketConnectDurationNanos != -1) {
            toStringHelper.add("socketConnectDurationNanos", socketConnectDurationNanos);
        }
        if (pendingAcquisitionDurationNanos != -1) {
            toStringHelper.add("pendingAcquisitionDurationNanos", pendingAcquisitionDurationNanos);
        }

        return toStringHelper.toString();
    }
}
