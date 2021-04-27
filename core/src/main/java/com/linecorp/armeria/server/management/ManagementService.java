/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.server.management;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link HttpService} that provides monitoring and management features.
 * First, you need to bind a {@link ManagementService} under a path.
 * <pre>{@code
 * Server.builder()
 *       .serviceUnder("/internal/management/", ManagementService.of())
 * }</pre>
 *
 * <h2>Thread dump</h2>
 * You can dump the thread information for all live threads with stack trace by accessing
 * {@code "/jvm/threaddump"}.
 * If {@link MediaType#JSON} is specified in {@link HttpHeaderNames#ACCEPT}, the thread information will be
 * converted to a JSON. Otherwise, the thread dump will be converted to a plain text.
 * <pre>{@code
 * // Exports thread information as a JSON array
 * curl -L -H "Accept: application/json" http://my-service.com/internal/management/jvm/threaddump
 * // Exports thread information as a plain text
 * curl -L -H "Accept: text/plain" http://my-service.com/internal/management/jvm/threaddump
 * }</pre>
 *
 * <h2>Heap dump</h2>
 * You can also dump the heap in the same format as the hprof heap dump by accessing
 * {@code "/jvm/heapdump"}.
 * <pre>{@code
 * curl -L http://my-service.com/internal/management/jvm/heapdump -o heapdump.hprof
 * // Dump only live objects that are reachable from others
 * curl -L http://my-service.com/internal/management/jvm/heapdump?live=true -o heapdump.hprof
 * }</pre>
 */
@UnstableApi
public final class ManagementService extends AbstractHttpService {

    private static final ManagementService INSTANCE = new ManagementService();

    /**
     * Returns a singleton {@link ManagementService}.
     */
    public static ManagementService of() {
        return INSTANCE;
    }

    ManagementService() {}

    @Override
    public HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final String path = ctx.mappedPath();
        switch (path) {
            case "/jvm/threaddump":
                return ThreadDumpService.INSTANCE.serve(ctx, req);
            case "/jvm/heapdump":
                return HeapDumpService.INSTANCE.serve(ctx, req);
            default:
                return HttpResponse.of(HttpStatus.NOT_FOUND);
        }
    }
}
