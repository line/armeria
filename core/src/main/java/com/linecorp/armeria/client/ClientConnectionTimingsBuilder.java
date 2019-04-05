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

import static com.google.common.base.Preconditions.checkState;

import com.linecorp.armeria.common.util.SystemInfo;

/**
 * Builds a new {@link ClientConnectionTimings}.
 */
public final class ClientConnectionTimingsBuilder {

    private final long acquiringConnectionStartMicros;
    private final long acquiringConnectionStartNanos;
    private long dnsResolutionEndNanos;

    private long socketConnectStartMicros;
    private long socketConnectStartNanos;
    private long socketConnectEndNanos;

    private long pendingAcquisitionStartMicros;
    private long pendingAcquisitionStartNanos;
    private long pendingAcquisitionEndNanos;

    /**
     * Creates a new instance.
     */
    public ClientConnectionTimingsBuilder() {
        acquiringConnectionStartMicros = SystemInfo.currentTimeMicros();
        acquiringConnectionStartNanos = System.nanoTime();
    }

    /**
     * Sets the time when resolving a domain name ends. If this method is invoked, the creation time of this
     * {@link ClientConnectionTimingsBuilder} is considered as the start time of resolving a domain name.
     */
    public ClientConnectionTimingsBuilder dnsResolutionEnd() {
        // The start time of dnsResolution is acquiringConnectionStartMicros and acquiringConnectionStartNanos.
        // So we don't have to call checkState() here.
        dnsResolutionEndNanos = System.nanoTime();
        return this;
    }

    /**
     * Sets the time when connecting to a remote peer started.
     */
    public ClientConnectionTimingsBuilder socketConnectStart() {
        socketConnectStartMicros = SystemInfo.currentTimeMicros();
        socketConnectStartNanos = System.nanoTime();
        return this;
    }

    /**
     * Sets the time when connecting to a remote peer ends.
     *
     * @throws IllegalStateException if {@link #socketConnectStart()} is not invoked before calling this.
     */
    public ClientConnectionTimingsBuilder socketConnectEnd() {
        checkState(socketConnectStartMicros >= 0, "socketConnectStart is not called yet.");
        socketConnectEndNanos = System.nanoTime();
        return this;
    }

    /**
     * Sets the time when waiting an ongoing connecting attempt started in order to use one connection
     * for HTTP/2.
     */
    public ClientConnectionTimingsBuilder pendingAcquisitionStart() {
        pendingAcquisitionStartMicros = SystemInfo.currentTimeMicros();
        pendingAcquisitionStartNanos = System.nanoTime();
        return this;
    }

    /**
     * Sets the time when waiting an ongoing connecting attempt ends in order to use one connection
     * for HTTP/2.
     *
     * @throws IllegalStateException if {@link #pendingAcquisitionStart()} is not invoked before calling this.
     */
    public ClientConnectionTimingsBuilder pendingAcquisitionEnd() {
        checkState(pendingAcquisitionStartMicros >= 0, "pendingAcquisitionStart is not called yet.");
        pendingAcquisitionEndNanos = System.nanoTime();
        return this;
    }

    /**
     * Returns a newly-created {@link ClientConnectionTimings} instance.
     */
    public ClientConnectionTimings build() {
        return new ClientConnectionTimings(
                acquiringConnectionStartMicros,
                System.nanoTime() - acquiringConnectionStartNanos,
                dnsResolutionEndNanos == 0 ? -1 : acquiringConnectionStartMicros,
                dnsResolutionEndNanos == 0 ? -1 : dnsResolutionEndNanos - acquiringConnectionStartNanos,
                socketConnectStartMicros == 0 ? -1 : socketConnectStartMicros,
                socketConnectEndNanos == 0 ? -1 : socketConnectEndNanos - socketConnectStartNanos,
                pendingAcquisitionStartMicros == 0 ? -1 : pendingAcquisitionStartMicros,
                pendingAcquisitionEndNanos == 0 ? -1 : pendingAcquisitionEndNanos -
                                                       pendingAcquisitionStartNanos);
    }
}
