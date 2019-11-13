/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.it.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.testing.internal.TestUtil.withTimeout;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingRpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingRpcService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

/**
 * Tests if Armeria decorators can alter the request/response timeout specified in Thrift call parameters
 * and disable the request/response timeout dynamically.
 */
class ThriftDynamicTimeoutTest {

    private static final SleepService.AsyncIface sleepService = (delay, resultHandler) ->
            ServiceRequestContext.current().eventLoop().schedule(
                    () -> resultHandler.onComplete(delay), delay, TimeUnit.MILLISECONDS);

    private static final SleepService.AsyncIface fakeSleepService = (delay, resultHandler) ->
            ServiceRequestContext.current().eventLoop().execute(
                    () -> resultHandler.onComplete(delay));

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Used for testing if changing the timeout dynamically works.
            sb.service("/sleep", ThriftCallService.of(sleepService)
                                                  .decorate(DynamicTimeoutService::new)
                                                  .decorate(THttpService.newDecorator()));
            // Used for testing if disabling the timeout dynamically works.
            sb.service("/fakeSleep", ThriftCallService.of(fakeSleepService)
                                                      .decorate(TimeoutDisablingService::new)
                                                      .decorate(THttpService.newDecorator()));
            sb.requestTimeout(Duration.ofSeconds(1));
        }
    };

    @ParameterizedTest
    @ArgumentsSource(ClientDecoratorProvider.class)
    void testDynamicTimeout(Function<
            Client<RpcRequest, RpcResponse>,
            Client<RpcRequest, RpcResponse>> clientDecorator) throws Exception {
        final SleepService.Iface client = new ClientBuilder(server.uri(BINARY, "/sleep"))
                .rpcDecorator(clientDecorator)
                .responseTimeout(Duration.ofSeconds(1)).build(SleepService.Iface.class);

        final long delay = 1500;
        final Stopwatch sw = Stopwatch.createStarted();
        client.sleep(delay);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(delay);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientDecoratorProvider.class)
    void testDisabledTimeout(Function<
            Client<RpcRequest, RpcResponse>,
            Client<RpcRequest, RpcResponse>> clientDecorator) throws Exception {
        withTimeout(() -> {
            final SleepService.Iface client = new ClientBuilder(server.uri(BINARY, "/fakeSleep"))
                    .rpcDecorator(clientDecorator)
                    .responseTimeout(Duration.ofSeconds(1)).build(SleepService.Iface.class);

            final long delay = 30000;
            final Stopwatch sw = Stopwatch.createStarted();
            // This call should take very short amount of time because the fakeSleep service does not sleep.
            client.sleep(delay);
            assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isLessThan(delay);
        });
    }

    /**
     * Runs the tests with:
     * - the client with dynamic timeout enabled and
     * - the client that disables timeout dynamically.
     */
    private static final class ClientDecoratorProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Function<Client<RpcRequest, RpcResponse>,
                    Client<RpcRequest, RpcResponse>> newDynamicTimeoutClient = DynamicTimeoutClient::new;
            final Function<Client<RpcRequest, RpcResponse>,
                    Client<RpcRequest, RpcResponse>> newTimeoutDisablingClient = TimeoutDisablingClient::new;
            return Stream.of(newDynamicTimeoutClient, newTimeoutDisablingClient).map(Arguments::of);
        }
    }

    private static final class DynamicTimeoutService extends SimpleDecoratingRpcService {

        DynamicTimeoutService(RpcService delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setRequestTimeoutMillis(((Number) req.params().get(0)).longValue() +
                                        ctx.requestTimeoutMillis());
            return delegate().serve(ctx, req);
        }
    }

    private static final class TimeoutDisablingService extends SimpleDecoratingRpcService {

        TimeoutDisablingService(RpcService delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setRequestTimeoutMillis(0);
            return delegate().serve(ctx, req);
        }
    }

    private static final class DynamicTimeoutClient extends SimpleDecoratingRpcClient {

        DynamicTimeoutClient(Client<RpcRequest, RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setResponseTimeoutMillis(((Number) req.params().get(0)).longValue() +
                                         ctx.responseTimeoutMillis());
            return delegate().execute(ctx, req);
        }
    }

    private static final class TimeoutDisablingClient extends SimpleDecoratingRpcClient {

        TimeoutDisablingClient(Client<RpcRequest, RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
            ctx.setResponseTimeoutMillis(0);
            return delegate().execute(ctx, req);
        }
    }
}
