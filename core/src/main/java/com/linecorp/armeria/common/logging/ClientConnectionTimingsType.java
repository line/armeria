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

package com.linecorp.armeria.common.logging;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Various client connection timings which may be used with {@link ClientConnectionTimings}
 * to retrieve start times and duration.
 */
@UnstableApi
public enum ClientConnectionTimingsType {
    /**
     * Represent the timing when the client resolves a domain name.
     */
    DNS_RESOLUTION,

    /**
     * Represent the timing when the client connects to a remote peer.
     */
    SOCKET_CONNECT,

    /**
     * Represent the timing when the client waits for the completion of a pending connection attempt.
     */
    PENDING_ACQUISITION,

    /**
     * Represent the timing when the client waits for the completion of an existing connection attempt.
     */
    EXISTING_ACQUISITION,
}
