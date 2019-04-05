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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;

import io.netty.util.AttributeKey;

/**
 * A holder class which has the start time and duration information about acquiring a connection
 * before a client sends a {@link Request}.
 */
public final class ClientConnectionTimings {

    private static final AttributeKey<ClientConnectionTimings> TIMINGS =
            AttributeKey.valueOf(ClientConnectionTimings.class, "TIMINGS");

    private final long acquiringConnectionStartMicros;
    private final long acquiringConnectionDurationNanos;

    private final long dnsResolutionStartMicros;
    private final long dnsResolutionDurationNanos;
    private final long socketConnectStartMicros;
    private final long socketConnectDurationNanos;
    private final long pendingAcquisitionStartMicros;
    private final long pendingAcquisitionDurationNanos;

    /**
     * Returns {@link ClientConnectionTimings} from the specified {@link RequestContext} if exists.
     * You can set a timings using {@link #setTo(RequestContext)}.
     */
    @Nullable
    public static ClientConnectionTimings get(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        if (ctx.hasAttr(TIMINGS)) {
            return ctx.attr(TIMINGS).get();
        }
        return null;
    }

    /**
     * Returns {@link ClientConnectionTimings} from the specified {@link RequestLog} if exists.
     * You can set a timings using {@link #setTo(RequestLog)}.
     */
    @Nullable
    public static ClientConnectionTimings get(RequestLog log) {
        requireNonNull(log, "log");
        if (log.hasAttr(TIMINGS)) {
            return log.attr(TIMINGS).get();
        }
        return null;
    }

    ClientConnectionTimings(long acquiringConnectionStartMicros, long acquiringConnectionDurationNanos,
                            long dnsResolutionStartMicros, long dnsResolutionDurationNanos,
                            long socketConnectStartMicros, long socketConnectDurationNanos,
                            long pendingAcquisitionStartMicros, long pendingAcquisitionDurationNanos) {
        this.acquiringConnectionStartMicros = acquiringConnectionStartMicros;
        this.acquiringConnectionDurationNanos = acquiringConnectionDurationNanos;
        this.dnsResolutionStartMicros = dnsResolutionStartMicros;
        this.dnsResolutionDurationNanos = dnsResolutionDurationNanos;
        this.socketConnectStartMicros = socketConnectStartMicros;
        this.socketConnectDurationNanos = socketConnectDurationNanos;
        this.pendingAcquisitionStartMicros = pendingAcquisitionStartMicros;
        this.pendingAcquisitionDurationNanos = pendingAcquisitionDurationNanos;
    }

    /**
     * Sets this {@link ClientConnectionTimings} to the specified {@link RequestContext}.
     * You can bring it back using {@link #get(RequestContext)}.
     */
    public void setTo(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        ctx.attr(TIMINGS).set(this);
    }

    /**
     * Sets this {@link ClientConnectionTimings} to the specified {@link RequestContext}.
     * You can bring it back using {@link #get(RequestLog)}.
     */
    public void setTo(RequestLog log) {
        requireNonNull(log, "log");
        log.attr(TIMINGS).set(this);
    }

    /**
     * Returns the time when acquiring a connection started, in microseconds since the epoch.
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
     * Returns the time when resolving a domain name started, in microseconds since the epoch.
     *
     * @return the duration, or {@code -1} if there was no action to resolve a domain name.
     */
    public long dnsResolutionStartMicros() {
        return dnsResolutionStartMicros;
    }

    /**
     * Returns the duration which was taken to resolve a domain name, in nanoseconds.
     *
     * @return the duration, or {@code -1} if there was no action to resolve a domain name.
     */
    public long dnsResolutionDurationNanos() {
        return dnsResolutionDurationNanos;
    }

    /**
     * Returns the time when connecting to a remote peer started, in microseconds since the epoch.
     *
     * @return the duration, or {@code -1} if there was no action to connect to a remote peer.
     */
    public long socketConnectStartMicros() {
        return socketConnectStartMicros;
    }

    /**
     * Returns the duration which was taken to connect to a remote peer, in nanoseconds.
     *
     * @return the duration, or {@code -1} if there was no action to connect to a remote peer.
     */
    public long socketConnectDurationNanos() {
        return socketConnectDurationNanos;
    }

    /**
     * Returns the time when waiting the completion of an ongoing connecting attempt started,
     * in microseconds since the epoch.
     *
     * @return the duration, or {@code -1} if there was no action to connect to a remote peer.
     */
    public long pendingAcquisitionStartMicros() {
        return pendingAcquisitionStartMicros;
    }

    /**
     * Returns the duration which was taken to wait the completion of an ongoing connecting attempt in order to
     * use one connection for HTTP/2.
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
        if (dnsResolutionDurationNanos >= 0) {
            toStringHelper.add("dnsResolutionStartMicros", dnsResolutionStartMicros);
            toStringHelper.add("dnsResolutionDurationNanos", dnsResolutionDurationNanos);
        }
        if (socketConnectDurationNanos >= 0) {
            toStringHelper.add("socketConnectStartMicros", socketConnectStartMicros);
            toStringHelper.add("socketConnectDurationNanos", socketConnectDurationNanos);
        }
        if (pendingAcquisitionDurationNanos >= 0) {
            toStringHelper.add("pendingAcquisitionStartMicros", pendingAcquisitionStartMicros);
            toStringHelper.add("pendingAcquisitionDurationNanos", pendingAcquisitionDurationNanos);
        }

        return toStringHelper.toString();
    }
}
