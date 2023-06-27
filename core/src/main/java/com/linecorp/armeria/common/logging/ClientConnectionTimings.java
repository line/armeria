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

package com.linecorp.armeria.common.logging;

import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.util.TextFormatter;

/**
 * A holder class which has the timing information about a connection attempt before a client
 * sends a {@link Request}.
 *
 * @see RequestLog#connectionTimings()
 */
public final class ClientConnectionTimings {

    @VisibleForTesting
    static final int TO_STRING_BUILDER_CAPACITY = 500;

    private final long connectionAcquisitionStartTimeMicros;
    private final long connectionAcquisitionDurationNanos;

    private final long dnsResolutionStartTimeMicros;
    private final long dnsResolutionDurationNanos;
    private final long socketConnectStartTimeMicros;
    private final long socketConnectDurationNanos;
    private final long pendingAcquisitionStartTimeMicros;
    private final long pendingAcquisitionDurationNanos;
    private final long existingAcquisitionStartTimeMicros;
    private final long existingAcquisitionDurationNanos;

    /**
     * Returns a newly created {@link ClientConnectionTimingsBuilder}.
     */
    public static ClientConnectionTimingsBuilder builder() {
        return new ClientConnectionTimingsBuilder();
    }

    ClientConnectionTimings(long connectionAcquisitionStartTimeMicros, long connectionAcquisitionDurationNanos,
                            long dnsResolutionStartTimeMicros, long dnsResolutionDurationNanos,
                            long socketConnectStartTimeMicros, long socketConnectDurationNanos,
                            long pendingAcquisitionStartTimeMicros, long pendingAcquisitionDurationNanos,
                            long existingAcquisitionStartTimeMicros,
                            long existingAcquisitionDurationNanos) {
        this.connectionAcquisitionStartTimeMicros = connectionAcquisitionStartTimeMicros;
        this.connectionAcquisitionDurationNanos = connectionAcquisitionDurationNanos;
        this.dnsResolutionStartTimeMicros = dnsResolutionStartTimeMicros;
        this.dnsResolutionDurationNanos = dnsResolutionDurationNanos;
        this.socketConnectStartTimeMicros = socketConnectStartTimeMicros;
        this.socketConnectDurationNanos = socketConnectDurationNanos;
        this.pendingAcquisitionStartTimeMicros = pendingAcquisitionStartTimeMicros;
        this.pendingAcquisitionDurationNanos = pendingAcquisitionDurationNanos;
        this.existingAcquisitionStartTimeMicros = existingAcquisitionStartTimeMicros;
        this.existingAcquisitionDurationNanos = existingAcquisitionDurationNanos;
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
     * equal to the sum of {@link ClientConnectionTimingsType#DNS_RESOLUTION},
     * {@link ClientConnectionTimingsType#SOCKET_CONNECT}
     * {@link ClientConnectionTimingsType#PENDING_ACQUISITION}
     * and {@link ClientConnectionTimingsType#EXISTING_ACQUISITION}.
     */
    public long connectionAcquisitionDurationNanos() {
        return connectionAcquisitionDurationNanos;
    }

    /**
     * Returns the time when the client started to resolve a domain name, in microseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to resolve a domain name.
     *
     * @deprecated use {@link #startTimeMicros(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
    public long dnsResolutionStartTimeMicros() {
        return dnsResolutionStartTimeMicros;
    }

    /**
     * Returns the time when the client started to resolve a domain name, in milliseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to resolve a domain name.
     *
     * @deprecated use {@link #startTimeMillis(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
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
     *
     * @deprecated use {@link #durationNanos(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
    public long dnsResolutionDurationNanos() {
        return dnsResolutionDurationNanos;
    }

    /**
     * Returns the time when the client started to connect to a remote peer, in microseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to connect to a remote peer.
     *
     * @deprecated use {@link #startTimeMicros(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
    public long socketConnectStartTimeMicros() {
        return socketConnectStartTimeMicros;
    }

    /**
     * Returns the time when the client started to connect to a remote peer, in milliseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to connect to a remote peer.
     *
     * @deprecated use {@link #startTimeMillis(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
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
     *
     * @deprecated use {@link #durationNanos(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
    public long socketConnectDurationNanos() {
        return socketConnectDurationNanos;
    }

    /**
     * Returns the time when the client started to wait for the completion of an existing connection attempt,
     * in microseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to get a pending connection.
     *
     * @deprecated use {@link #startTimeMicros(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
    public long pendingAcquisitionStartTimeMicros() {
        return pendingAcquisitionStartTimeMicros;
    }

    /**
     * Returns the time when the client started to wait for the completion of an existing connection attempt,
     * in milliseconds since the epoch.
     *
     * @return the start time, or {@code -1} if there was no action to get a pending connection.
     *
     * @deprecated use {@link #startTimeMillis(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
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
     *
     * @deprecated use {@link #durationNanos(ClientConnectionTimingsType)} instead.
     */
    @Deprecated
    public long pendingAcquisitionDurationNanos() {
        return pendingAcquisitionDurationNanos;
    }

    /**
     * Returns the time when the client started the action for the specified
     * {@link ClientConnectionTimingsType} in microseconds.
     *
     * @return the start time, or {@code -1} if there was no action.
     */
    public long startTimeMicros(ClientConnectionTimingsType type) {
        switch (type) {
            case SOCKET_CONNECT:
                return socketConnectStartTimeMicros;
            case DNS_RESOLUTION:
                return dnsResolutionStartTimeMicros;
            case PENDING_ACQUISITION:
                return pendingAcquisitionStartTimeMicros;
            case EXISTING_ACQUISITION:
                return existingAcquisitionStartTimeMicros;
            default:
                throw new Error("Shouldn't reach here");
        }
    }

    /**
     * Returns the time when the client started the action for the specified
     * {@link ClientConnectionTimingsType} in milliseconds.
     *
     * @return the start time, or {@code -1} if there was no action.
     */
    public long startTimeMillis(ClientConnectionTimingsType type) {
        return TimeUnit.MICROSECONDS.toMillis(startTimeMicros(type));
    }

    /**
     * Returns the duration which was taken to wait for the completion of the action
     * in nanoseconds.
     *
     * @return the duration, or {@code -1} if there was no action.
     */
    public long durationNanos(ClientConnectionTimingsType type) {
        switch (type) {
            case SOCKET_CONNECT:
                return socketConnectDurationNanos;
            case DNS_RESOLUTION:
                return dnsResolutionDurationNanos;
            case PENDING_ACQUISITION:
                return pendingAcquisitionDurationNanos;
            case EXISTING_ACQUISITION:
                return existingAcquisitionDurationNanos;
            default:
                throw new Error("Shouldn't reach here");
        }
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder(TO_STRING_BUILDER_CAPACITY);
        buf.append("{connectionAcquisitionStartTime=");
        TextFormatter.appendEpochMicros(buf, connectionAcquisitionStartTimeMicros);
        buf.append(", connectionAcquisitionDuration=");
        TextFormatter.appendElapsed(buf, connectionAcquisitionDurationNanos);

        if (dnsResolutionDurationNanos >= 0) {
            buf.append(", dnsResolution.startTime=");
            TextFormatter.appendEpochMicros(buf, dnsResolutionStartTimeMicros);
            buf.append(", dnsResolution.duration=");
            TextFormatter.appendElapsed(buf, dnsResolutionDurationNanos);
        }

        if (socketConnectDurationNanos >= 0) {
            buf.append(", socketConnect.startTime=");
            TextFormatter.appendEpochMicros(buf, socketConnectStartTimeMicros);
            buf.append(", socketConnect.duration=");
            TextFormatter.appendElapsed(buf, socketConnectDurationNanos);
        }
        if (pendingAcquisitionDurationNanos >= 0) {
            buf.append(", pendingAcquisition.startTime=");
            TextFormatter.appendEpochMicros(buf, pendingAcquisitionStartTimeMicros);
            buf.append(", pendingAcquisition.duration=");
            TextFormatter.appendElapsed(buf, pendingAcquisitionDurationNanos);
        }
        if (existingAcquisitionDurationNanos >= 0) {
            buf.append(", existingAcquisition.startTime=");
            TextFormatter.appendEpochMicros(buf, existingAcquisitionStartTimeMicros);
            buf.append(", existingAcquisition.duration=");
            TextFormatter.appendElapsed(buf, existingAcquisitionDurationNanos);
        }
        buf.append('}');
        return buf.toString();
    }
}
