/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Sets properties for building {@link DynamicEndpointGroup}.
 */
@UnstableApi
public interface DynamicEndpointGroupSetters<SELF extends DynamicEndpointGroupSetters<SELF>> {

    /**
     * Sets whether to allow an empty {@link Endpoint} list.
     * If unspecified, an empty {@link Endpoint} list is allowed.
     */
    SELF allowEmptyEndpoints(boolean allowEmptyEndpoints);

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@link Duration#ZERO} disables the timeout.
     * If unspecified, {@link Flags#defaultConnectTimeoutMillis()} is used by default.
     */
    @UnstableApi
    default SELF selectionTimeout(Duration selectionTimeout) {
        requireNonNull(selectionTimeout, "selectionTimeout");
        checkArgument(!selectionTimeout.isNegative(), "selectionTimeout: %s (expected: >= 0)",
                      selectionTimeout);
        return selectionTimeoutMillis(selectionTimeout.toMillis());
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@code 0} disables the timeout.
     * If unspecified, {@link Flags#defaultConnectTimeoutMillis()} is used by default.
     */
    @UnstableApi
    SELF selectionTimeoutMillis(long selectionTimeoutMillis);
}
