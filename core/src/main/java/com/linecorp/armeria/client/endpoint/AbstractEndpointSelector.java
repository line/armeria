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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;

/**
 * A skeletal {@link EndpointSelector} implementation. This abstract class implements the
 * {@link #select(ClientRequestContext, ScheduledExecutorService)} method by listening to
 * the change events emitted by {@link EndpointGroup} specified at construction time.
 */
public abstract class AbstractEndpointSelector extends AbstractAsyncSelector<Endpoint>
        implements EndpointSelector {

    private final EndpointGroup endpointGroup;

    /**
     * Creates a new instance that selects an {@link Endpoint} from the specified {@link EndpointGroup}.
     */
    protected AbstractEndpointSelector(EndpointGroup endpointGroup) {
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
    }

    /**
     * Returns the {@link EndpointGroup} being selected by this {@link EndpointSelector}.
     */
    protected final EndpointGroup group() {
        return endpointGroup;
    }

    /**
     * Initialize this {@link EndpointSelector} to listen to the new endpoints emitted by the
     * {@link EndpointGroup}. The new endpoints will be passed to {@link #updateNewEndpoints(List)}.
     */
    @UnstableApi
    protected final void initialize() {
        endpointGroup.addListener(this::refreshEndpoints, true);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return super.select(ctx, executor, endpointGroup.selectionTimeoutMillis());
    }

    private void refreshEndpoints(List<Endpoint> endpoints) {
        // Allow subclasses to update the endpoints first.
        updateNewEndpoints(endpoints);
        refresh();
    }

    @Override
    protected void onTimeout(ClientRequestContext ctx, long selectionTimeoutMillis) {
        final EndpointSelectionTimeoutException ex =
                EndpointSelectionTimeoutException.get(endpointGroup, selectionTimeoutMillis);
        ClientPendingThrowableUtil.setPendingThrowable(ctx, ex);
    }

    /**
     * Invoked when the {@link EndpointGroup} has been updated.
     */
    @UnstableApi
    protected void updateNewEndpoints(List<Endpoint> endpoints) {}
}
