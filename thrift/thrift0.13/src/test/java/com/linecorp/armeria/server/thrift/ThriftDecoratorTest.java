/*
 * Copyright 2023 LINE Corporation
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

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.thrift.TException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class ThriftDecoratorTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/", THttpService.of(new MyHelloService()));
        }
    };

    private static final BlockingDeque<String> decorators = new LinkedBlockingDeque<>();

    @Decorator(FirstDecorator.class)
    private static class MyHelloService implements HelloService.Iface {
        @Decorator(MethodFirstDecorator.class)
        @Override
        public String hello(String name) throws TException {
            return "Hello World";
        }
    }

    @BeforeEach
    void setUp() {
        decorators.clear();
    }

    private static class FirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("FirstDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("MethodFirstDecorator");
            return delegate.serve(ctx, req);
        }
    }

    @Test
    void methodDecorators() throws TException {
       HelloService.Iface iface = ThriftClients.newClient(server.httpUri(), HelloService.Iface.class);

       assertThat(iface.hello("")).isEqualTo("Hello World");

       final String first = decorators.poll();
       final String second = decorators.poll();
       assertThat(first).isEqualTo("FirstDecorator");
       assertThat(second).isEqualTo("MethodFirstDecorator");
       assertThat(decorators).isEmpty();
    }
}
