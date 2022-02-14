/*
 * Copyright 2020 LINE Corporation
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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An {@link EndpointGroupException} raised when the resolution of an {@link EndpointGroup} fails
 * because there are no {@link Endpoint}s in the {@link EndpointGroup}.
 */
public final class EmptyEndpointGroupException extends EndpointGroupException {

    private static final long serialVersionUID = 7595286618131200852L;

    static final EmptyEndpointGroupException INSTANCE = new EmptyEndpointGroupException(false);

    /**
     * Returns an {@link EmptyEndpointGroupException} which may be a singleton or a new instance, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static EmptyEndpointGroupException get(@Nullable EndpointGroup endpointGroup) {
        return Flags.verboseExceptionSampler().isSampled(EmptyEndpointGroupException.class) ?
               new EmptyEndpointGroupException(endpointGroup) : INSTANCE;
    }

    @Nullable
    private static String endpointGroupString(@Nullable EndpointGroup endpointGroup) {
        if (endpointGroup == null) {
            return null;
        }
        return MoreObjects.toStringHelper(endpointGroup).omitNullValues()
                          .add("selectionStrategy", endpointGroup.selectionStrategy().getClass())
                          .add("initialized", endpointGroup.whenReady().isDone())
                          .add("numEndpoints", endpointGroup.endpoints().size()).toString();
    }

    private EmptyEndpointGroupException(@Nullable EndpointGroup endpointGroup) {
        super(endpointGroupString(endpointGroup));
    }

    private EmptyEndpointGroupException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
