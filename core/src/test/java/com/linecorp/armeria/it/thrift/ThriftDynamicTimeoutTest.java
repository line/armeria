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
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.test.AbstractServerTest;

/**
 * Tests if Armeria decorators can alter the request/response timeout specified in Thrift call parameters.
 */
public class ThriftDynamicTimeoutTest extends AbstractServerTest {

    private static final SleepService.AsyncIface sleepService = (delay, resultHandler) ->
            RequestContext.current().eventLoop().schedule(
                    () -> resultHandler.onComplete(delay), delay, TimeUnit.MILLISECONDS);

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.serviceAt("/sleep", ThriftCallService.of(sleepService)
                                                .decorate(DynamicTimeoutService::new)
                                                .decorate(THttpService.newDecorator()));
        sb.defaultRequestTimeout(Duration.ofSeconds(1));
    }

    @Test
    public void testDynamicTimeout() throws Exception {
        final SleepService.Iface client = new ClientBuilder("tbinary+" + uri("/sleep"))
                .decorator(ThriftCall.class, ThriftReply.class, DynamicTimeoutClient::new)
                .defaultResponseTimeout(Duration.ofSeconds(1)).build(SleepService.Iface.class);

        final long delay = 1500;
        final Stopwatch sw = Stopwatch.createStarted();
        client.sleep(delay);
        assertThat(sw.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(delay);
    }

    private static final class DynamicTimeoutService
            extends DecoratingService<ThriftCall, ThriftReply, ThriftCall, ThriftReply> {

        DynamicTimeoutService(Service<? super ThriftCall, ? extends ThriftReply> delegate) {
            super(delegate);
        }

        @Override
        public ThriftReply serve(ServiceRequestContext ctx, ThriftCall req) throws Exception {
            ctx.setRequestTimeoutMillis(((Number) req.params().get(0)).longValue() +
                                        ctx.requestTimeoutMillis());
            return delegate().serve(ctx, req);
        }
    }

    private static final class DynamicTimeoutClient
            extends DecoratingClient<ThriftCall, ThriftReply, ThriftCall, ThriftReply> {

        DynamicTimeoutClient(Client<? super ThriftCall, ? extends ThriftReply> delegate) {
            super(delegate);
        }

        @Override
        public ThriftReply execute(ClientRequestContext ctx, ThriftCall req) throws Exception {
            ctx.setResponseTimeoutMillis(((Number) req.params().get(0)).longValue() +
                                         ctx.responseTimeoutMillis());
            return delegate().execute(ctx, req);
        }
    }
}
