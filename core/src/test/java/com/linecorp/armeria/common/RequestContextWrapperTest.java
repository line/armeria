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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Supplier;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.google.errorprone.annotations.MustBeClosed;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextWrapper;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextWrapper;

class RequestContextWrapperTest {

    static {
        // Armeria properties use bare names.
        Assertions.setExtractBareNamePropertyMethods(true);
    }

    private static final class WrappedRequestContext extends RequestContextWrapper<RequestContext> {
        private WrappedRequestContext(RequestContext delegate) {
            super(delegate);
        }

        // Most wrappers will not want to push the delegate so we don't provide a default implementation of it.
        @Override
        @MustBeClosed
        public SafeCloseable push() {
            return unwrap().push();
        }

        @Override
        public void hook(Supplier<? extends AutoCloseable> contextHook) {
            unwrap().hook(contextHook);
        }

        @Override
        public Supplier<AutoCloseable> hook() {
            return unwrap().hook();
        }
    }

    @Test
    void wrapMatchesNormal() {
        final RequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        // Use reflective comparison to handle added properties automatically.
        assertThat(new WrappedRequestContext(ctx)).usingRecursiveComparison().ignoringFields("delegate")
                                                  .isEqualTo(ctx);
    }

    @Test
    void testServiceUnwrapBehavior() {
        final RequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final WrappedRequestContext wrapped1 = new WrappedRequestContext(ctx);
        final WrappedRequestContext wrapped2 = new WrappedRequestContext(wrapped1);
        assertThat(wrapped2.unwrap()).isSameAs(wrapped1);
        assertThat(wrapped1.unwrap()).isSameAs(ctx);

        final DefaultServiceRequestContext as = wrapped2.as(DefaultServiceRequestContext.class);
        assertThat(as).isSameAs(ctx);

        final RequestContextExtension extension = wrapped2.as(RequestContextExtension.class);
        assertThat(extension).isSameAs(ctx);

        final RequestContext unwrapped1 = wrapped1.unwrapAll();
        assertThat(unwrapped1).isSameAs(ctx);
        final RequestContext unwrapped2 = wrapped2.unwrapAll();
        assertThat(unwrapped2).isSameAs(ctx);
    }

    @Test
    void testClientUnwrapBehavior() {
        final RequestContext ctx = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final WrappedRequestContext wrapped1 = new WrappedRequestContext(ctx);
        final WrappedRequestContext wrapped2 = new WrappedRequestContext(wrapped1);
        assertThat(wrapped2.unwrap()).isSameAs(wrapped1);
        assertThat(wrapped1.unwrap()).isSameAs(ctx);

        final DefaultClientRequestContext as = wrapped2.as(DefaultClientRequestContext.class);
        assertThat(as).isSameAs(ctx);

        final ClientRequestContextExtension clientExtension = wrapped2.as(ClientRequestContextExtension.class);
        assertThat(clientExtension).isSameAs(ctx);

        final RequestContextExtension extension = wrapped2.as(RequestContextExtension.class);
        assertThat(extension).isSameAs(ctx);
    }

    @Test
    void testUnwrappedTypes() {
        final RequestContext requestContext =
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        final RequestContext unwrappedRequestContext = requestContext.unwrap();
        assertThat(unwrappedRequestContext).isSameAs(requestContext);

        final ClientRequestContext clientRequestContext =
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        ClientRequestContext unwrappedClientRequestContext = clientRequestContext.unwrap();
        assertThat(unwrappedClientRequestContext).isSameAs(clientRequestContext);
        unwrappedClientRequestContext = clientRequestContext.unwrapAll();
        assertThat(unwrappedClientRequestContext).isSameAs(clientRequestContext);

        final ServiceRequestContext serviceRequestContext =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        ServiceRequestContext unwrappedServiceRequestContext = serviceRequestContext.unwrap();
        assertThat(unwrappedServiceRequestContext).isSameAs(serviceRequestContext);
        unwrappedServiceRequestContext = serviceRequestContext.unwrapAll();
        assertThat(unwrappedServiceRequestContext).isSameAs(serviceRequestContext);

        final ClientRequestContextWrapper clientRequestContextWrapper =
                new ClientRequestContextWrapper(clientRequestContext) {};
        unwrappedClientRequestContext = clientRequestContextWrapper.unwrap();
        assertThat(unwrappedClientRequestContext).isSameAs(clientRequestContext);
        unwrappedClientRequestContext = clientRequestContext.unwrapAll();
        assertThat(unwrappedClientRequestContext).isSameAs(clientRequestContext);

        final ServiceRequestContextWrapper serviceRequestContextWrapper =
                new ServiceRequestContextWrapper(serviceRequestContext) {};
        unwrappedServiceRequestContext = serviceRequestContextWrapper.unwrap();
        assertThat(unwrappedServiceRequestContext).isSameAs(serviceRequestContext);
        unwrappedServiceRequestContext = serviceRequestContextWrapper.unwrapAll();
        assertThat(unwrappedServiceRequestContext).isSameAs(serviceRequestContext);
    }
}
