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

package com.linecorp.armeria.internal.spring;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to provide security functionality of internal service.
 */
public class SecurityInternalService extends SimpleDecoratingHttpService {

    /**
     * Creates security {@link HttpService} decorator using the specified ports.
     *
     * @param ports the array of ports.
     */
    public static Function<? super HttpService, SecurityInternalService> newDecorator(
            Integer... ports) {
        return newDecorator(ImmutableList.copyOf(ports));
    }

    /**
     * Creates security {@link HttpService} decorator using the specified ports.
     *
     * @param ports a list of ports.
     */
    public static Function<? super HttpService, SecurityInternalService> newDecorator(
            Iterable<Integer> ports) {
        requireNonNull(ports, "ports");
        return delegate -> new SecurityInternalService(delegate, ports);
    }

    private final List<Integer> ports;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    protected SecurityInternalService(HttpService delegate, Iterable<Integer> port) {
        super(delegate);
        ports = ImmutableList.copyOf(port);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final InetSocketAddress laddr = ctx.localAddress();
        if (ports.contains(laddr.getPort())) {
            return HttpResponse.of(404);
        } else {
            return delegate().serve(ctx, req);
        }
    }
}
