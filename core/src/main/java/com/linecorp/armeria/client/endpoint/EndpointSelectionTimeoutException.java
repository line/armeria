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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An {@link EndpointGroupException} raised when an {@link EndpointGroup} fails to resolve
 * an {@link Endpoint} within a configured selection timeout.
 */
@UnstableApi
public final class EndpointSelectionTimeoutException extends EndpointGroupException {

    private static final long serialVersionUID = -3079582212067997365L;

    private static final EndpointSelectionTimeoutException INSTANCE =
            new EndpointSelectionTimeoutException();

    /**
     * Returns an {@link EndpointSelectionTimeoutException} which prints a message about
     * the {@link EndpointGroup} when thrown.
     */
    public static EndpointSelectionTimeoutException get(EndpointGroup endpointGroup,
                                                        long selectionTimeoutMillis) {
        requireNonNull(endpointGroup, "endpointGroup");
        checkArgument(selectionTimeoutMillis >= 0, "selectionTimeoutMillis: %s (expected: >= 0)",
                      selectionTimeoutMillis);
        return Flags.verboseExceptionSampler().isSampled(EndpointSelectionTimeoutException.class) ?
               new EndpointSelectionTimeoutException(endpointGroup, selectionTimeoutMillis) : INSTANCE;
    }

    private EndpointSelectionTimeoutException() {}

    private EndpointSelectionTimeoutException(EndpointGroup endpointGroup, long selectionTimeoutMillis) {
        super("Failed to select within " + selectionTimeoutMillis + " ms an endpoint from: " + endpointGroup);
    }
}
