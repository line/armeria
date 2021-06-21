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

package com.linecorp.armeria.testing.junit5.server;

import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Function;

/**
 * Captures the {@link ServiceRequestContext}s.
 */
public final class ServiceRequestContextCaptor {
    private final BlockingQueue<ServiceRequestContext> serviceContexts =
            new LinkedBlockingDeque<>();

    /**
     * Creates a new decorator to capture the {@link ServiceRequestContext}s.
     */
    public Function<? super HttpService, HttpService> decorator() {
        return delegate -> (HttpService) (ctx, req) -> {
            serviceContexts.add(ctx);
            return delegate.serve(ctx, req);
        };
    }

    /**
     * Clears the captured {@link ServiceRequestContext}s.
     */
    public void clear() {
        serviceContexts.clear();
    }

    /**
     * Returns the number of captured {@link ServiceRequestContext}s.
     */
    public int size() {
        return serviceContexts.size();
    }

    /**
     * Retrieves and removes the first captured {@link ServiceRequestContext}, waiting if necessary
     * until an element becomes available.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public ServiceRequestContext take() throws InterruptedException {
        return serviceContexts.take();
    }
}
