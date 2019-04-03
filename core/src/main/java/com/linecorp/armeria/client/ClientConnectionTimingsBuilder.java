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

import com.google.common.annotations.VisibleForTesting;

/**
 * Builds a new {@link ClientConnectionTimings}.
 */
@VisibleForTesting
public class ClientConnectionTimingsBuilder {

    private final long acquiringConnectionStartMicros;
    private final long acquiringConnectionStartNanos;

    private long dnsResolutionEndNanos;
    private long socketConnectStartNanos;
    private long socketConnectEndNanos;
    private long pendingAcquisitionStartNanos;
    private long pendingAcquisitionEndNanos;

    /**
     * Creates a new instance.
     */
    @VisibleForTesting
    public ClientConnectionTimingsBuilder(long acquiringConnectionStartMicros,
                                          long acquiringConnectionStartNanos) {
        this.acquiringConnectionStartMicros = acquiringConnectionStartMicros;
        this.acquiringConnectionStartNanos = acquiringConnectionStartNanos;
    }

    void dnsResolutionEndNanos(long dnsResolutionEndNanos) {
        this.dnsResolutionEndNanos = dnsResolutionEndNanos;
    }

    void socketConnectStartNanos(long socketConnectStartNanos) {
        this.socketConnectStartNanos = socketConnectStartNanos;
    }

    void socketConnectEndNanos(long socketConnectEndNanos) {
        this.socketConnectEndNanos = socketConnectEndNanos;
    }

    void pendingAcquisitionStartNanos(long pendingAcquisitionStartNanos) {
        this.pendingAcquisitionStartNanos = pendingAcquisitionStartNanos;
    }

    void pendingAcquisitionEndNanos(long pendingAcquisitionEndNanos) {
        this.pendingAcquisitionEndNanos = pendingAcquisitionEndNanos;
    }

    /**
     * Returns a newly-created {@link ClientConnectionTimings} instance.
     */
    @VisibleForTesting
    public ClientConnectionTimings build(long acquiringConnectionEndNanos) {
        return new ClientConnectionTimings(
                acquiringConnectionStartMicros,
                acquiringConnectionEndNanos - acquiringConnectionStartNanos,
                dnsResolutionEndNanos == 0 ? -1 : dnsResolutionEndNanos - acquiringConnectionStartNanos,
                socketConnectEndNanos == 0 ? -1 : socketConnectEndNanos - socketConnectStartNanos,
                pendingAcquisitionEndNanos == 0 ? -1 : pendingAcquisitionEndNanos -
                                                       pendingAcquisitionStartNanos);
    }
}
