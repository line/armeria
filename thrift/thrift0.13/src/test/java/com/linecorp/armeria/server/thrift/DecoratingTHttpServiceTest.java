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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Function;

import org.apache.thrift.TException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.SimpleDecoratingRpcService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;

class DecoratingTHttpServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", THttpService.builder()
                                        .decorate(GlobalRpcDecorator.newDecorator())
                                        .addService(new DecoratedHelloService())
                                        .build());
        }
    };

    private static final BlockingDeque<String> decorators = new LinkedBlockingDeque<>();

    @BeforeEach
    void setUp() {
        decorators.clear();
    }

    @Test
    public void decorateTHttpService() throws TException {
        final HelloService.Iface iface = ThriftClients.newClient(server.httpUri(), HelloService.Iface.class);

        assertThat(iface.hello("foo")).isEqualTo("Hello foo!");

        assertThat(decorators.poll()).isEqualTo("ClassFirstDecorator");
        assertThat(decorators.poll()).isEqualTo("ClassSecondDecorator");
        assertThat(decorators.poll()).isEqualTo("MethodFirstDecorator");
        assertThat(decorators.poll()).isEqualTo("MethodSecondDecorator");
        assertThat(decorators.poll()).isEqualTo("CustomDecorator");
        // Rpc decorators will be executed later than http decorators
        assertThat(decorators.poll()).isEqualTo("GlobalRpcDecorator");
        assertThat(decorators).isEmpty();
    }

    @DecoratorFactory(CustomDecoratorFunction.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface CustomDecorator {
        int order() default 0;
    }

    private static final class GlobalRpcDecorator extends SimpleDecoratingRpcService {
        private GlobalRpcDecorator(RpcService delegate) {
            super(delegate);
        }

        private static Function<? super RpcService, GlobalRpcDecorator> newDecorator() {
            return GlobalRpcDecorator::new;
        }

        @Override
        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
            decorators.offer("GlobalRpcDecorator");
            return unwrap().serve(ctx, req);
        }
    }

    private static class ClassFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            decorators.offer("ClassFirstDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class ClassSecondDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            decorators.offer("ClassSecondDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            decorators.offer("MethodFirstDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodSecondDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            decorators.offer("MethodSecondDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class CustomDecoratorFunction implements DecoratorFactoryFunction<CustomDecorator> {
        @Override
        public Function<? super HttpService, ? extends HttpService> newDecorator(CustomDecorator parameter) {
            return service -> new SimpleDecoratingHttpService(service) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    decorators.offer("CustomDecorator");
                    return service.serve(ctx, req);
                }
            };
        }
    }

    @Decorator(value = ClassSecondDecorator.class, order = 2)
    @Decorator(value = ClassFirstDecorator.class, order = 1)
    private static class DecoratedHelloService implements HelloService.Iface {

        @Decorator(value = MethodFirstDecorator.class, order = 3)
        @Decorator(value = MethodSecondDecorator.class, order = 4)
        @CustomDecorator(order = 5)
        @Override
        public String hello(String name) throws TException {
            return "Hello " + name + '!';
        }
    }
}
