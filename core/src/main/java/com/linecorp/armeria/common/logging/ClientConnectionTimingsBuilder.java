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

import static com.google.common.base.Preconditions.checkState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.SystemInfo;

/**
 * Builds a new {@link ClientConnectionTimings}.
 */
public final class ClientConnectionTimingsBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ClientConnectionTimingsBuilder.class);

    private final long connectionAcquisitionStartTimeMicros;
    private final long connectionAcquisitionStartNanos;
    private long tlsHandshakeStartTimeMicros;
    private long tlsHandshakeStartNanos;
    private long tlsHandshakeEndNanos;
    private boolean tlsHandshakeEndSet;
    private long dnsResolutionEndNanos;
    private boolean dnsResolutionEndSet;

    private long socketConnectStartTimeMicros;
    private long socketConnectStartNanos;
    private long socketConnectEndNanos;
    private boolean socketConnectEndSet;

    private long pendingAcquisitionStartTimeMicros;
    private long pendingAcquisitionStartNanos;
    private long pendingAcquisitionEndNanos;
    private boolean pendingAcquisitionEndSet;

    ClientConnectionTimingsBuilder() {
        connectionAcquisitionStartTimeMicros = SystemInfo.currentTimeMicros();
        connectionAcquisitionStartNanos = System.nanoTime();
    }

    /**
     * Sets the time when the client ended to resolve a domain name. If this method is invoked, the creation
     * time of this {@link ClientConnectionTimingsBuilder} is considered as the start time of
     * resolving a domain name.
     */
    public ClientConnectionTimingsBuilder dnsResolutionEnd() {
        checkState(!dnsResolutionEndSet, "dnsResolutionEnd() is already called.");
        dnsResolutionEndNanos = System.nanoTime();
        dnsResolutionEndSet = true;
        return this;
    }

    /**
     * Sets the time when the client started to connect to a remote peer.
     */
    public ClientConnectionTimingsBuilder socketConnectStart() {
        socketConnectStartTimeMicros = SystemInfo.currentTimeMicros();
        socketConnectStartNanos = System.nanoTime();
        return this;
    }

    /**
     * Sets the time when the client ended to connect to a remote peer.
     *
     * @throws IllegalStateException if {@link #socketConnectStart()} is not invoked before calling this.
     */
    public ClientConnectionTimingsBuilder socketConnectEnd() {
        checkState(socketConnectStartTimeMicros > 0, "socketConnectStart() is not called yet.");
        checkState(!socketConnectEndSet, "socketConnectEnd() is already called.");
        socketConnectEndNanos = System.nanoTime();
        socketConnectEndSet = true;
        return this;
    }

    /**
     * Sets the time when the client started to TLS handshake to a remote peer.
     */
    public ClientConnectionTimingsBuilder tlsHandshakeStart() {
        tlsHandshakeStartTimeMicros = SystemInfo.currentTimeMicros();
        tlsHandshakeStartNanos = System.nanoTime();
        return this;
    }

    /**
     * Sets the time when the client ended to TLS handshake to a remote peer.
     *
     * @throws IllegalStateException if {@link #tlsHandshakeStart()} is not invoked before calling this.
     */
    public ClientConnectionTimingsBuilder tlsHandshakeEnd() {
        checkState(tlsHandshakeStartTimeMicros > 0, "tlsHandshakeStart() is not called yet.");
        checkState(!tlsHandshakeEndSet, "tlsHandshakeEnd() is already called.");
        tlsHandshakeEndNanos = System.nanoTime();
        tlsHandshakeEndSet = true;
        return this;
    }

    /**
     * Sets the time when the client started to wait for the completion of an existing connection attempt
     * in order to use one connection for HTTP/2.
     */
    public ClientConnectionTimingsBuilder pendingAcquisitionStart() {
        if (pendingAcquisitionStartTimeMicros == 0 && !pendingAcquisitionEndSet) {
            pendingAcquisitionStartTimeMicros = SystemInfo.currentTimeMicros();
            pendingAcquisitionStartNanos = System.nanoTime();
        }
        return this;
    }

    /**
     * Sets the time when the client ended to wait for an existing connection attempt in order to use
     * one connection for HTTP/2.
     *
     * @throws IllegalStateException if {@link #pendingAcquisitionStart()} is not invoked before calling this.
     */
    public ClientConnectionTimingsBuilder pendingAcquisitionEnd() {
        checkState(pendingAcquisitionStartTimeMicros > 0, "pendingAcquisitionStart() is not called yet.");
        pendingAcquisitionEndNanos = System.nanoTime();
        pendingAcquisitionEndSet = true;
        return this;
    }

    /**
     * Returns a newly-created {@link ClientConnectionTimings} instance.
     */
    public ClientConnectionTimings build() {
        if (socketConnectStartTimeMicros > 0 && !socketConnectEndSet) {
            logger.warn("Should call socketConnectEnd() if socketConnectStart() was invoked.");
        }
        if (pendingAcquisitionStartTimeMicros > 0 && !pendingAcquisitionEndSet) {
            logger.warn("Should call pendingAcquisitionEnd() if pendingAcquisitionStart() was invoked.");
        }

        return new ClientConnectionTimings(
                connectionAcquisitionStartTimeMicros,
                System.nanoTime() - connectionAcquisitionStartNanos,
                dnsResolutionEndSet ? connectionAcquisitionStartTimeMicros : -1,
                dnsResolutionEndSet ? dnsResolutionEndNanos - connectionAcquisitionStartNanos : -1,
                socketConnectEndSet ? socketConnectStartTimeMicros : -1,
                socketConnectEndSet ? socketConnectEndNanos - socketConnectStartNanos : -1,
                tlsHandshakeEndSet ? tlsHandshakeStartTimeMicros : -1,
                tlsHandshakeEndSet ? tlsHandshakeEndNanos - tlsHandshakeStartNanos : -1,
                pendingAcquisitionEndSet ? pendingAcquisitionStartTimeMicros : -1,
                pendingAcquisitionEndSet ? pendingAcquisitionEndNanos - pendingAcquisitionStartNanos : -1);
    }
}
