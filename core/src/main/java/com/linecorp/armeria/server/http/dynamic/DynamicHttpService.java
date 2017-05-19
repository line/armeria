/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.dynamic;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceNotFoundException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.server.http.HttpService;

/**
 * An {@link HttpService} that serves dynamic contents.
 */
public class DynamicHttpService extends AbstractHttpService {

    private final List<DynamicHttpFunctionEntry> entries;

    /**
     * Create a {@link DynamicHttpService} instance.
     */
    protected DynamicHttpService() {
        this.entries = ImmutableList.copyOf(Methods.entries(this, Collections.emptyMap()));
    }

    DynamicHttpService(Iterable<DynamicHttpFunctionEntry> entries) {
        this.entries = ImmutableList.copyOf(entries);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        HttpMethod method = req.method();
        String mappedPath = ctx.mappedPath();

        for (DynamicHttpFunctionEntry entry : entries) {
            MappedDynamicFunction mappedDynamicFunction = entry.bind(method, mappedPath);
            if (mappedDynamicFunction != null) {
                return mappedDynamicFunction.serve(ctx, req);
            }
        }

        throw ServiceNotFoundException.get();
    }

    /**
     * Creates a new {@link HttpService} that tries this {@link DynamicHttpService} first and then the specified
     * {@link HttpService} when this {@link DynamicHttpService} cannot handle the request.
     *
     * @param nextService the {@link HttpService} to try secondly
     */
    public HttpService orElse(Service<?, ? extends HttpResponse> nextService) {
        requireNonNull(nextService, "nextService");
        return new OrElseHttpService(this, nextService);
    }

    private static final class OrElseHttpService extends AbstractHttpService {

        private final DynamicHttpService first;
        private final Service<Request, HttpResponse> second;

        @SuppressWarnings("unchecked")
        OrElseHttpService(DynamicHttpService first, Service<?, ? extends HttpResponse> second) {
            this.first = first;
            this.second = (Service<Request, HttpResponse>) second;
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            first.serviceAdded(cfg);
            second.serviceAdded(cfg);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            try {
                return first.serve(ctx, req);
            } catch (ServiceNotFoundException e) {
                return second.serve(ctx, req);
            }
        }
    }
}
