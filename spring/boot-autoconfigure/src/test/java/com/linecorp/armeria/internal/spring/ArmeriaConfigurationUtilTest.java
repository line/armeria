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

import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureAnnotatedServices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.spring.AnnotatedServiceRegistrationBean;
import com.linecorp.armeria.spring.MeterIdPrefixFunctionFactory;

class ArmeriaConfigurationUtilTest {

    @Test
    void makesSureDecoratorsAreConfigured() {
        final Function<? super HttpService, ? extends HttpService> decorator = spy(new IdentityFunction());
        final AnnotatedServiceRegistrationBean bean = new AnnotatedServiceRegistrationBean()
                .setServiceName("test")
                .setService(new SimpleService())
                .setDecorators(decorator);

        final ServerBuilder sb1 = Server.builder();
        final DocServiceBuilder dsb1 = DocService.builder();
        configureAnnotatedServices(sb1, dsb1, ImmutableList.of(bean),
                                       MeterIdPrefixFunctionFactory.ofDefault(), null);
        final Server s1 = sb1.build();
        verify(decorator, times(2)).apply(any());
        assertThat(service(s1).as(MetricCollectingService.class)).isNotNull();

        reset(decorator);

        final ServerBuilder sb2 = Server.builder();
        final DocServiceBuilder dsb2 = DocService.builder();
        configureAnnotatedServices(sb2, dsb2, ImmutableList.of(bean),
                                       null, null);
        final Server s2 = sb2.build();
        verify(decorator, times(2)).apply(any());
        assertThat(getServiceForHttpMethod(sb2.build(), HttpMethod.OPTIONS))
                .isInstanceOf(AnnotatedService.class);
    }

    @Test
    void makesSureDecoratedServiceIsAdded() {
        final Function<? super HttpService, ? extends HttpService> decorator = spy(new DecoratingFunction());
        final AnnotatedServiceRegistrationBean bean = new AnnotatedServiceRegistrationBean()
                .setServiceName("test")
                .setService(new SimpleService())
                .setDecorators(decorator);

        final ServerBuilder sb = Server.builder();
        final DocServiceBuilder dsb = DocService.builder();
        configureAnnotatedServices(sb, dsb, ImmutableList.of(bean), null, null);
        final Server s = sb.build();
        verify(decorator, times(2)).apply(any());
        assertThat(service(s).as(SimpleDecorator.class)).isNotNull();
    }

    private static HttpService service(Server server) {
        return server.config().defaultVirtualHost().serviceConfigs().get(0).service();
    }

    private static HttpService getServiceForHttpMethod(Server server, HttpMethod httpMethod) {
        return server.serviceConfigs().stream()
                     .filter(config -> config.route().methods().contains(httpMethod))
                     .findFirst().get().service();
    }

    /**
     * A decorator function which is the same as {@link #identity()} but is not a final class.
     */
    static class IdentityFunction implements Function<HttpService, HttpService> {
        @Override
        public HttpService apply(HttpService delegate) {
            return delegate;
        }
    }

    /**
     * A simple decorating function.
     */
    static class DecoratingFunction implements Function<HttpService, HttpService> {
        @Override
        public HttpService apply(HttpService delegate) {
            return new SimpleDecorator(delegate);
        }
    }

    static class SimpleDecorator extends SimpleDecoratingHttpService {
        SimpleDecorator(HttpService delegate) {
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
        // with AnnotatedService#ExceptionFilteredHttpResponseDecorator.
        @Get
        @Options
        @Path("/")
        public String root() {
            return "Hello, world!";
        }
    }
}
