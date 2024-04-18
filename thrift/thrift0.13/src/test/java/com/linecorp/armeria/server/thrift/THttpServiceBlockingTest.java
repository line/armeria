/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.test.TestService;
import testing.thrift.test.TestServiceRequest;
import testing.thrift.test.TestServiceResponse;

class THttpServiceBlockingTest {
    private static final AtomicBoolean blocking = new AtomicBoolean();

    private static final ScheduledExecutorService executor =
            new ScheduledThreadPoolExecutor(1, ThreadFactories.newThreadFactory("blocking-test", true)) {
                @Override
                protected void beforeExecute(Thread t, Runnable r) {
                    blocking.set(true);
                }
            };

    @BeforeEach
    void clear() {
        blocking.set(false);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.service("/",
                       THttpService.builder().addService(new TestServiceImpl()).build());
            sb.service("/blocking", THttpService.builder()
                                                .useBlockingTaskExecutor(true)
                                                .addService(new TestServiceImpl())
                                                .build());
            sb.blockingTaskExecutor(executor, true);
        }
    };

    @Test
    void nonBlocking() throws Exception {
        final TestService.Iface client = ThriftClients.newClient(server.httpUri(), TestService.Iface.class);

        final String message = "nonBlockingTest";
        final TestServiceResponse response = client.get(new TestServiceRequest(message));

        assertThat(response.response).isEqualTo(message);
        assertThat(blocking).isFalse();
    }

    @Test
    void blocking() throws Exception {
        final TestService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .decorator((delegate, ctx, req) -> {
                                 final HttpRequest newReq = req.mapHeaders(
                                         headers -> headers.toBuilder().path("/blocking").build());
                                 ctx.updateRequest(newReq);
                                 return delegate.execute(ctx, newReq);
                             })
                             .build(TestService.Iface.class);
        final String message = "blockingTest";
        final TestServiceResponse response = client.get(new TestServiceRequest(message));

        assertThat(response.response).isEqualTo(message);
        assertThat(blocking).isTrue();
    }

    static class TestServiceImpl implements TestService.AsyncIface {
        @Override
        public void get(TestServiceRequest request,
                        AsyncMethodCallback resultHandler) throws TException {
            resultHandler.onComplete(new TestServiceResponse(request.getMessage()));
        }
    }
}
