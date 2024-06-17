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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;

class THttpServiceBlockingTest {
    private static final AtomicReference<String> currentThreadName = new AtomicReference<>("");

    private static final String BLOCKING_EXECUTOR_PREFIX = "blocking-test";
    private static final ScheduledExecutorService executor =
            new ScheduledThreadPoolExecutor(1,
                                            ThreadFactories.newThreadFactory(BLOCKING_EXECUTOR_PREFIX, true));

    @BeforeEach
    void clearDetector() {
        currentThreadName.set("");
    }

    @AfterAll
    public static void shutdownExecutor() {
        executor.shutdown();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.service("/",
                       THttpService.builder().addService(new HelloServiceAsyncImpl()).build());
            sb.service("/blocking", THttpService.builder()
                                                .useBlockingTaskExecutor(true)
                                                .addService(new HelloServiceAsyncImpl())
                                                .build());
            sb.service("/blocking-iface",
                       THttpService.builder().addService(new HelloServiceImpl()).build());

            sb.blockingTaskExecutor(executor, true);
        }
    };

    @Test
    void nonBlocking() throws Exception {
        final HelloService.Iface client = ThriftClients.newClient(server.httpUri(), HelloService.Iface.class);

        final String message = "nonBlockingTest";
        final String response = client.hello(message);

        assertThat(response).isEqualTo(message);
        assertThat(currentThreadName.get().startsWith(BLOCKING_EXECUTOR_PREFIX)).isFalse();
    }

    @Test
    void blocking() throws Exception {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/blocking")
                             .build(HelloService.Iface.class);
        final String message = "blockingTest";
        final String response = client.hello(message);

        assertThat(response).isEqualTo(message);
        assertThat(currentThreadName.get().startsWith(BLOCKING_EXECUTOR_PREFIX)).isTrue();
    }

    @Test
    void blockingIface() throws Exception {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/blocking-iface")
                             .build(HelloService.Iface.class);
        final String message = "blockingTest";
        final String response = client.hello(message);

        assertThat(response).isEqualTo(message);
        assertThat(currentThreadName.get().startsWith(BLOCKING_EXECUTOR_PREFIX)).isTrue();
    }

    static class HelloServiceAsyncImpl implements HelloService.AsyncIface {
        @Override
        public void hello(String name, AsyncMethodCallback resultHandler) throws TException {
            currentThreadName.set(Thread.currentThread().getName());
            resultHandler.onComplete(name);
        }
    }

    static class HelloServiceImpl implements HelloService.Iface {
        @Override
        public String hello(String name) throws TException {
            currentThreadName.set(Thread.currentThread().getName());
            return name;
        }
    }
}
