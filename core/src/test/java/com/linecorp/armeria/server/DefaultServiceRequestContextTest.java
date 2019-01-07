/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

public class DefaultServiceRequestContextTest {

    @Test
    public void deriveContext() {
        final VirtualHost virtualHost = virtualHost();
        final DefaultPathMappingContext mappingCtx = new DefaultPathMappingContext(
                virtualHost, "example.com", HttpMethod.GET, "/hello", null, MediaType.JSON_UTF_8,
                ImmutableList.of(MediaType.JSON_UTF_8, MediaType.XML_UTF_8));

        final ServiceRequestContext originalCtx = new DefaultServiceRequestContext(
                virtualHost.serviceConfigs().get(0), mock(Channel.class), NoopMeterRegistry.get(),
                SessionProtocol.H2,
                mappingCtx, PathMappingResult.of("/foo"),
                mock(Request.class), null, null, NetUtil.LOCALHOST4);

        setAdditionalHeaders(originalCtx);
        setAdditionalTrailers(originalCtx);

        final AttributeKey<String> foo = AttributeKey.valueOf(DefaultServiceRequestContextTest.class, "foo");
        originalCtx.attr(foo).set("foo");

        final Request newRequest = mock(Request.class);
        final ServiceRequestContext derivedCtx = originalCtx.newDerivedContext(newRequest);
        assertThat(derivedCtx.server()).isSameAs(originalCtx.server());
        assertThat(derivedCtx.sessionProtocol()).isSameAs(originalCtx.sessionProtocol());
        assertThat(derivedCtx.<Service<HttpRequest, HttpResponse>>service()).isSameAs(originalCtx.service());
        assertThat(derivedCtx.pathMapping()).isSameAs(originalCtx.pathMapping());
        assertThat(derivedCtx.<Request>request()).isSameAs(newRequest);

        assertThat(derivedCtx.path()).isEqualTo(originalCtx.path());
        assertThat(derivedCtx.maxRequestLength()).isEqualTo(originalCtx.maxRequestLength());
        assertThat(derivedCtx.requestTimeoutMillis()).isEqualTo(originalCtx.requestTimeoutMillis());
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#1"))).isNull();
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#2")))
                .isEqualTo("value#2");
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#3")))
                .isEqualTo("value#3");
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#4")))
                .isEqualTo("value#4");
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#1"))).isNull();
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#2")))
                .isEqualTo("value#2");
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#3")))
                .isEqualTo("value#3");
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#4")))
                .isEqualTo("value#4");
        // the attribute is derived as well
        assertThat(derivedCtx.attr(foo).get()).isEqualTo("foo");

        // log is different
        assertThat(derivedCtx.log()).isNotSameAs(originalCtx.log());

        final AttributeKey<String> bar = AttributeKey.valueOf(DefaultServiceRequestContextTest.class, "bar");
        originalCtx.attr(bar).set("bar");

        // the Attribute added to the original context after creation is not propagated to the derived context
        assertThat(derivedCtx.attr(bar).get()).isEqualTo(null);
    }

    private static VirtualHost virtualHost() {
        final HttpService service = mock(HttpService.class);
        final Server server = new ServerBuilder().withVirtualHost("example.com")
                                                 .serviceUnder("/", service)
                                                 .and().build();
        return server.config().findVirtualHost("example.com");
    }

    private static void setAdditionalHeaders(ServiceRequestContext originalCtx) {
        final DefaultHttpHeaders headers1 = new DefaultHttpHeaders();
        headers1.set(HttpHeaderNames.of("my-header#1"), "value#1");
        originalCtx.setAdditionalResponseHeaders(headers1);
        originalCtx.setAdditionalResponseHeader(HttpHeaderNames.of("my-header#2"), "value#2");

        final DefaultHttpHeaders headers2 = new DefaultHttpHeaders();
        headers2.set(HttpHeaderNames.of("my-header#3"), "value#3");
        originalCtx.addAdditionalResponseHeaders(headers2);
        originalCtx.addAdditionalResponseHeader(HttpHeaderNames.of("my-header#4"), "value#4");
        // Remove the first one.
        originalCtx.removeAdditionalResponseHeader(HttpHeaderNames.of("my-header#1"));
    }

    private static void setAdditionalTrailers(ServiceRequestContext originalCtx) {
        final DefaultHttpHeaders trailers1 = new DefaultHttpHeaders();
        trailers1.set(HttpHeaderNames.of("my-trailer#1"), "value#1");
        originalCtx.setAdditionalResponseTrailers(trailers1);
        originalCtx.setAdditionalResponseTrailer(HttpHeaderNames.of("my-trailer#2"), "value#2");

        final DefaultHttpHeaders trailers2 = new DefaultHttpHeaders();
        trailers2.set(HttpHeaderNames.of("my-trailer#3"), "value#3");
        originalCtx.addAdditionalResponseTrailers(trailers2);
        originalCtx.addAdditionalResponseTrailer(HttpHeaderNames.of("my-trailer#4"), "value#4");
        // Remove the first one.
        originalCtx.removeAdditionalResponseTrailer(HttpHeaderNames.of("my-trailer#1"));
    }
}
