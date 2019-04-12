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

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.TextFormatter;

import io.netty.util.AttributeKey;

/**
 * A holder class which has the timing information about a connection attempt before a client
 * sends a {@link Request}.
 */
public final class ClientConnectionTimings {

    private static final AttributeKey<ClientConnectionTimings> TIMINGS =
            AttributeKey.valueOf(ClientConnectionTimings.class, "TIMINGS");

    @VisibleForTesting
    static final int TO_STRING_BUILDER_CAPACITY = 466;

    private final long connectionAcquisitionStartTimeMicros;
    private final long connectionAcquisitionDurationNanos;

    private final long dnsResolutionStartTimeMicros;
    private final long dnsResolutionDurationNanos;
    private final long socketConnectStartTimeMicros;
    private final long socketConnectDurationNanos;
    private final long pendingAcquisitionStartTimeMicros;
    private final long pendingAcquisitionDurationNanos;

    /**
     * Returns {@link ClientConnectionTimings} from the specified {@link RequestContext} if exists.
     *
     * @see #setTo(RequestContext)
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
     *
     * @see #setTo(RequestLog)
     */
    @Nullable
    public static ClientConnectionTimings get(RequestLog log) {
        requireNonNull(log, "log");
        if (log.hasAttr(TIMINGS)) {
            return log.attr(TIMINGS).get();
        }
        return null;
    }

    ClientConnectionTimings(long connectionAcquisitionStartTimeMicros, long connectionAcquisitionDurationNanos,
                            long dnsResolutionStartTimeMicros, long dnsResolutionDurationNanos,
                            long socketConnectStartTimeMicros, long socketConnectDurationNanos,
                            long pendingAcquisitionStartTimeMicros, long pendingAcquisitionDurationNanos) {
        this.connectionAcquisitionStartTimeMicros = connectionAcquisitionStartTimeMicros;
        this.connectionAcquisitionDurationNanos = connectionAcquisitionDurationNanos;
        this.dnsResolutionStartTimeMicros = dnsResolutionStartTimeMicros;
        this.dnsResolutionDurationNanos = dnsResolutionDurationNanos;
        this.socketConnectStartTimeMicros = socketConnectStartTimeMicros;
        this.socketConnectDurationNanos = socketConnectDurationNanos;
        this.pendingAcquisitionStartTimeMicros = pendingAcquisitionStartTimeMicros;
        this.pendingAcquisitionDurationNanos = pendingAcquisitionDurationNanos;
    }

    /**
     * Sets this {@link ClientConnectionTimings} to the specified {@link RequestContext}.
     * Note that this method is intended for internal use. Do not use unless you really need to.
     *
     * @see #get(RequestContext)
     */
    public void setTo(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        ctx.attr(TIMINGS).set(this);
    }

    /**
     * Sets this {@link ClientConnectionTimings} to the specified {@link RequestLog}.
     * Note that this method is intended for internal use. Do not use unless you really need to.
     *
     * @see #get(RequestLog)
     */
    public void setTo(RequestLog log) {
        requireNonNull(log, "log");
        log.attr(TIMINGS).set(this);
    }

    /**
     * Returns the time when the client started to acquire a connection, in microseconds since the epoch.
     */
    public long connectionAcquisitionStartTimeMicros() {
        return connectionAcquisitionStartTimeMicros;
    }

    /**
     * Returns the time when the client started to acquire a connection, in milliseconds since the epoch.
     */
    public long connectionAcquisitionStartTimeMillis() {
        return TimeUnit.MICROSECONDS.toMillis(connectionAcquisitionStartTimeMicros);
    }

    /**
     * Returns the duration which was taken to get a connection, in nanoseconds. This value is greater than or
     * equal to the sum of {@link #dnsResolutionDurationNanos()}, {@link #socketConnectDurationNanos()} and
     * {@link #pendingAcquisitionDurationNanos()}.
     */
    public long connectionAcquisitionDurationNanos() {
        return connectionAcquisitionDurationNanos;
    }

    /**
     * Returns the time when the client started to resolve a domain name, in microseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to resolve a domain name.
     */
    public long dnsResolutionStartTimeMicros() {
        return dnsResolutionStartTimeMicros;
    }

    /**
     * Returns the time when the client started to resolve a domain name, in milliseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to resolve a domain name.
     */
    public long dnsResolutionStartTimeMillis() {
        if (dnsResolutionStartTimeMicros >= 0) {
            return TimeUnit.MICROSECONDS.toMillis(dnsResolutionStartTimeMicros);
        }
        return -1;
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
     * Returns the time when the client started to connect to a remote peer, in microseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to connect to a remote peer.
     */
    public long socketConnectStartTimeMicros() {
        return socketConnectStartTimeMicros;
    }

    /**
     * Returns the time when the client started to connect to a remote peer, in milliseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to connect to a remote peer.
     */
    public long socketConnectStartTimeMillis() {
        if (socketConnectStartTimeMicros >= 0) {
            return TimeUnit.MICROSECONDS.toMillis(socketConnectStartTimeMicros);
        }
        return -1;
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
     * Returns the time when the client started to wait for the completion of an existing connection attempt,
     * in microseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to get a pending connection.
     */
    public long pendingAcquisitionStartTimeMicros() {
        return pendingAcquisitionStartTimeMicros;
    }

    /**
     * Returns the time when the client started to wait for the completion of an existing connection attempt,
     * in milliseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to get a pending connection.
     */
    public long pendingAcquisitionStartTimeMillis() {
        if (pendingAcquisitionStartTimeMicros >= 0) {
            return TimeUnit.MICROSECONDS.toMillis(pendingAcquisitionStartTimeMicros);
        }
        return -1;
    }

    /**
     * Returns the duration which was taken to wait for the completion of an existing connection attempt
     * in order to use one connection for HTTP/2.
     *
     * @return the duration, or {@code -1} if there was no action to get a pending connection.
     */
    public long pendingAcquisitionDurationNanos() {
        return pendingAcquisitionDurationNanos;
    }

    @Override
    public String toString() {
        // 33 + 31 + 26 + 23 + 26 + 23 + 31 + 28 + 45 * 4 + 16 * 4 + 1 = 466
        final StringBuilder buf = new StringBuilder(TO_STRING_BUILDER_CAPACITY);
        buf.append("{connectionAcquisitionStartTime=");
        TextFormatter.appendEpochMicros(buf, connectionAcquisitionStartTimeMicros);
        buf.append(", connectionAcquisitionDuration=");
        TextFormatter.appendElapsed(buf, connectionAcquisitionDurationNanos);

        if (dnsResolutionDurationNanos >= 0) {
            buf.append(", dnsResolutionStartTime=");
            TextFormatter.appendEpochMicros(buf, dnsResolutionStartTimeMicros);
            buf.append(", dnsResolutionDuration=");
            TextFormatter.appendElapsed(buf, dnsResolutionDurationNanos);
        }

        if (socketConnectDurationNanos >= 0) {
            buf.append(", socketConnectStartTime=");
            TextFormatter.appendEpochMicros(buf, socketConnectStartTimeMicros);
            buf.append(", socketConnectDuration=");
            TextFormatter.appendElapsed(buf, socketConnectDurationNanos);
        }
        if (pendingAcquisitionDurationNanos >= 0) {
            buf.append(", pendingAcquisitionStartTime=");
            TextFormatter.appendEpochMicros(buf, pendingAcquisitionStartTimeMicros);
            buf.append(", pendingAcquisitionDuration=");
            TextFormatter.appendElapsed(buf, pendingAcquisitionDurationNanos);
        }
        buf.append('}');
        return buf.toString();
    }
}
