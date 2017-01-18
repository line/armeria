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
package com.linecorp.armeria.it.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.test.AbstractServerTest;

/**
 * Tests if Armeria decorators can alter the request/response timeout specified in Thrift call parameters
 * and disable the request/response timeout dynamically.
 */
@RunWith(Parameterized.class)
public class ThriftDynamicTimeoutTest extends AbstractServerTest {

    private static final SleepService.AsyncIface sleepService = (delay, resultHandler) ->
            RequestContext.current().eventLoop().schedule(
                    () -> resultHandler.onComplete(delay), delay, TimeUnit.MILLISECONDS);

    private static final SleepService.AsyncIface fakeSleepService = (delay, resultHandler) ->
            RequestContext.current().eventLoop().execute(
                    () -> resultHandler.onComplete(delay));

    /**
     * Runs the tests with:
     * - the client with dynamic timeout enabled and
     * - the client that disables timeout dynamically.
     */
    @Parameters
    public static Collection<Function<Client<RpcRequest, RpcResponse>,
                                      Client<RpcRequest, RpcResponse>>> parameters() {
        return Arrays.asList(DynamicTimeoutClient::new, TimeoutDisablingClient::new);
    }

    private final Function<Client<RpcRequest, RpcResponse>,
            Client<RpcRequest, RpcResponse>> clientDecorator;

    public ThriftDynamicTimeoutTest(Function<Client<RpcRequest, RpcResponse>,
            Client<RpcRequest, RpcResponse>> clientDecorator) {
        this.clientDecorator = clientDecorator;
    }

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        // Used for testing if changing the timeout dynamically works.
        sb.serviceAt("/sleep", ThriftCallService.of(sleepService)
                                                .decorate(DynamicTimeoutService::new)
                                                .decorate(THttpService.newDecorator()));
        // Used for testing if disabling the timeout dynamically works.
        sb.serviceAt("/fakeSleep", ThriftCallService.of(fakeSleepService)
                                                    .decorate(TimeoutDisablingService::new)
                                                    .decorate(THttpService.newDecorator()));
        sb.defaultRequestTimeout(Duration.ofSeconds(1));
    }

    @Test
    public void testDynamicTimeout() throws Exception {
        final SleepService.Iface client = new ClientBuilder("tbinary+" + uri("/sleep"))
                .decorator(RpcRequest.class, RpcResponse.class, clientDecorator)
                .defaultResponseTimeout(Duration.ofSeconds(1)).build(SleepService.Iface.class);

        final long delay = 1500;
        final Stopwatch sw = Stopwatch.createStarted();
        client.sleep(delay);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(delay);
    }

    @Test(timeout = 10000)
    public void testDisabledTimeout() throws Exception {
        final SleepService.Iface client = new ClientBuilder("tbinary+" + uri("/fakeSleep"))
                .decorator(RpcRequest.class, RpcResponse.class, clientDecorator)
                .defaultResponseTimeout(Duration.ofSeconds(1)).build(SleepService.Iface.class);

        // This call should take very short amount of time because the fakeSleep service does not sleep.
        client.sleep(30000);
    }

    private static final class DynamicTimeoutService
            extends DecoratingService<RpcRequest, RpcResponse, RpcRequest, RpcResponse> {

        DynamicTimeoutService(Service<? super RpcRequest, ? extends RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setRequestTimeoutMillis(((Number) req.params().get(0)).longValue() +
                                        ctx.requestTimeoutMillis());
            return delegate().serve(ctx, req);
        }
    }


    private static final class TimeoutDisablingService
            extends DecoratingService<RpcRequest, RpcResponse, RpcRequest, RpcResponse> {

        TimeoutDisablingService(Service<? super RpcRequest, ? extends RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setRequestTimeoutMillis(0);
            return delegate().serve(ctx, req);
        }
    }

    private static final class DynamicTimeoutClient
            extends DecoratingClient<RpcRequest, RpcResponse, RpcRequest, RpcResponse> {

        DynamicTimeoutClient(Client<? super RpcRequest, ? extends RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setResponseTimeoutMillis(((Number) req.params().get(0)).longValue() +
                                         ctx.responseTimeoutMillis());
            return delegate().execute(ctx, req);
        }
    }

    private static final class TimeoutDisablingClient
            extends DecoratingClient<RpcRequest, RpcResponse, RpcRequest, RpcResponse> {

        TimeoutDisablingClient(Client<? super RpcRequest, ? extends RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setResponseTimeoutMillis(0);
            return delegate().execute(ctx, req);
        }
    }
}
