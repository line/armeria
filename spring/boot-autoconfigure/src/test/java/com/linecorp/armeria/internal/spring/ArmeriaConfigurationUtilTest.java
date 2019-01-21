/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureAnnotatedHttpServices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.function.Function;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.spring.AnnotatedServiceRegistrationBean;
import com.linecorp.armeria.spring.MeterIdPrefixFunctionFactory;

public class ArmeriaConfigurationUtilTest {

    @Test
    public void makesSureDecoratorsAreConfigured() {
        final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator = spy(new IdentityFunction());
        final AnnotatedServiceRegistrationBean bean = new AnnotatedServiceRegistrationBean()
                .setServiceName("test")
                .setService(new SimpleService())
                .setDecorators(decorator);

        final ServerBuilder sb1 = new ServerBuilder();
        configureAnnotatedHttpServices(sb1, ImmutableList.of(bean), MeterIdPrefixFunctionFactory.DEFAULT);
        verify(decorator).apply(any());
        assertThat(service(sb1).as(MetricCollectingService.class)).isPresent();

        reset(decorator);

        final ServerBuilder sb2 = new ServerBuilder();
        configureAnnotatedHttpServices(sb2, ImmutableList.of(bean), null);
        verify(decorator).apply(any());
        assertThat(service(sb2)).isInstanceOf(AnnotatedHttpService.class);
    }

    @Test
    public void makesSureDecoratedServiceIsAdded() {
        final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator = spy(new DecoratingFunction());
        final AnnotatedServiceRegistrationBean bean = new AnnotatedServiceRegistrationBean()
                .setServiceName("test")
                .setService(new SimpleService())
                .setDecorators(decorator);

        final ServerBuilder sb = new ServerBuilder();
        configureAnnotatedHttpServices(sb, ImmutableList.of(bean), null);
        verify(decorator).apply(any());
        assertThat(service(sb).as(SimpleDecorator.class)).isPresent();
    }

    private static Service<?, ?> service(ServerBuilder sb) {
        final Server server = sb.build();
        return server.config().defaultVirtualHost().serviceConfigs().get(0).service();
    }

    /**
     * A decorator function which is the same as {@link #identity()} but is not a final class.
     */
    static class IdentityFunction
            implements Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> {
        @Override
        public Service<HttpRequest, HttpResponse> apply(Service<HttpRequest, HttpResponse> delegate) {
            return delegate;
        }
    }

    /**
     * A simple decorating function.
     */
    static class DecoratingFunction
            implements Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> {
        @Override
        public Service<HttpRequest, HttpResponse> apply(Service<HttpRequest, HttpResponse> delegate) {
            return new SimpleDecorator(delegate);
        }
    }

    static class SimpleDecorator
            extends SimpleDecoratingService<HttpRequest, HttpResponse> {
        SimpleDecorator(Service<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.NO_CONTENT);
        }
    }

    /**
     * A simple annotated HTTP service.
     */
    static class SimpleService {
        // We need to specify '@Options' annotation in order to avoid adding a decorator which denies
        // a CORS preflight request. If any decorator is added, the service will be automatically decorated
        // with AnnotatedHttpService#ExceptionFilteredHttpResponseDecorator.
        @Get
        @Options
        @Path("/")
        public String root() {
            return "Hello, world!";
        }
    }
}
