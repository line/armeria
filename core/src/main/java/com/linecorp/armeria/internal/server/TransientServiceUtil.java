/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.internal.server;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.RouteDecoratingService.InitialDispatcherService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientService;
import com.linecorp.armeria.server.TransientServiceOption;

import io.netty.util.AttributeKey;

/**
 * Utilities for resolving the {@link TransientService} that handles a request.
 *
 * <p>A {@link TransientService} can be specified not only as the service of a {@link ServiceConfig} but also
 * as a route decorator, e.g. {@code ServerBuilder.decorator(path, TransientHttpService.newDecorator())}.
 * The route decorators matching a request are only known at request time, so the {@link TransientService}
 * is resolved lazily per request by looking up the matching route decorators first and then the service,
 * in the same order as {@link ServiceRequestContext#findService(Class)}. The first {@link TransientService}
 * found wins and the result is cached in the {@link ServiceRequestContext}. If none of the route decorators
 * is a {@link TransientService}, the value computed from the {@link ServiceConfig#service()} in advance is
 * used without looking up the route decorators.
 */
public final class TransientServiceUtil {

    private static final AttributeKey<ResolvedTransientService> RESOLVED_TRANSIENT_SERVICE =
            AttributeKey.valueOf(TransientServiceUtil.class, "RESOLVED_TRANSIENT_SERVICE");

    /**
     * Returns {@code true} if the request is handled by a {@link TransientService} which is either
     * a matching route decorator or the {@link ServiceConfig#service()}.
     */
    public static boolean isTransientService(ServiceRequestContext ctx) {
        final HttpService service = ctx.config().service();
        final InitialDispatcherService dispatcher = transientServiceDecoratorDispatcher(service);
        if (dispatcher == null) {
            return service.as(TransientService.class) != null;
        }
        return resolve(ctx, dispatcher).isTransientService;
    }

    /**
     * Returns the {@link Set} of {@link TransientServiceOption}s that are enabled for the request.
     * The {@link TransientServiceOption}s of the first {@link TransientService} found from the matching
     * route decorators and the {@link ServiceConfig#service()} are returned.
     * {@link TransientServiceOption#allOf()} is returned if no {@link TransientService} is found.
     */
    public static Set<TransientServiceOption> transientServiceOptions(ServiceRequestContext ctx) {
        final ServiceConfig config = ctx.config();
        final InitialDispatcherService dispatcher = transientServiceDecoratorDispatcher(config.service());
        if (dispatcher == null) {
            // No TransientService route decorators. Use the options extracted from the service in advance.
            return config.transientServiceOptions();
        }
        return resolve(ctx, dispatcher).transientServiceOptions;
    }

    /**
     * Returns the {@link InitialDispatcherService} if the specified {@link HttpService} has a route decorator
     * which is a {@link TransientService}. Otherwise, {@code null} is returned so that the caller can use
     * the value computed from the {@link ServiceConfig#service()} without looking up the route decorators.
     */
    @Nullable
    private static InitialDispatcherService transientServiceDecoratorDispatcher(HttpService service) {
        if (!(service instanceof InitialDispatcherService)) {
            return null;
        }
        final InitialDispatcherService dispatcher = (InitialDispatcherService) service;
        return dispatcher.hasTransientServiceDecorator() ? dispatcher : null;
    }

    private static ResolvedTransientService resolve(ServiceRequestContext ctx,
                                                    InitialDispatcherService service) {
        final ResolvedTransientService cached = ctx.attr(RESOLVED_TRANSIENT_SERVICE);
        if (cached != null) {
            return cached;
        }

        final TransientService<?, ?> transientService = service.findService(ctx, TransientService.class);
        final ResolvedTransientService resolved;
        if (transientService == null) {
            resolved = ResolvedTransientService.NON_TRANSIENT_SERVICE;
        } else {
            final Set<TransientServiceOption> transientServiceOptions = requireNonNull(
                    transientService.transientServiceOptions(),
                    "transientServiceOptions() returned null by " + transientService);
            resolved = new ResolvedTransientService(true, transientServiceOptions);
        }
        // The result is always the same for the same request, so it is fine to overwrite the value
        // computed concurrently by another thread.
        ctx.setAttr(RESOLVED_TRANSIENT_SERVICE, resolved);
        return resolved;
    }

    private static final class ResolvedTransientService {

        static final ResolvedTransientService NON_TRANSIENT_SERVICE =
                new ResolvedTransientService(false, TransientServiceOption.allOf());

        final boolean isTransientService;
        final Set<TransientServiceOption> transientServiceOptions;

        ResolvedTransientService(boolean isTransientService,
                                 Set<TransientServiceOption> transientServiceOptions) {
            this.isTransientService = isTransientService;
            this.transientServiceOptions = transientServiceOptions;
        }
    }

    private TransientServiceUtil() {}
}
