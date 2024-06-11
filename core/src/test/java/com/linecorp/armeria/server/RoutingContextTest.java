/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

class RoutingContextTest {

    @Test
    void testAcceptTypes() {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.GET, "/",
                HttpHeaderNames.ACCEPT,
                "application/xml;q=0.9, " +
                "*/*;q=0.8, " +
                "text/html;charset=UTF-8, " +
                "application/xhtml+xml;charset=utf-8");
        final List<MediaType> acceptTypes = headers.accept();
        assertThat(acceptTypes).containsExactly(MediaType.HTML_UTF_8,
                                                MediaType.XHTML_UTF_8,
                                                MediaType.parse("application/xml;q=0.9"),
                                                MediaType.parse("*/*;q=0.8"));
    }

    @Test
    void testEquals() {
        final VirtualHost virtualHost = virtualHost();
        final RequestTarget reqTarget = RequestTarget.forServer("/hello");
        assertThat(reqTarget).isNotNull();

        final RoutingContext ctx1 =
                new DefaultRoutingContext(virtualHost, "example.com",
                                          RequestHeaders.of(HttpMethod.GET, "/hello",
                                                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                            HttpHeaderNames.ACCEPT,
                                                            MediaType.JSON_UTF_8 + ", " +
                                                            MediaType.XML_UTF_8 + "; q=0.8"),
                                          reqTarget, RoutingStatus.OK, SessionProtocol.H2C);
        final RoutingContext ctx2 =
                new DefaultRoutingContext(virtualHost, "example.com",
                                          RequestHeaders.of(HttpMethod.GET, "/hello",
                                                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                            HttpHeaderNames.ACCEPT,
                                                            MediaType.JSON_UTF_8 + ", " +
                                                            MediaType.XML_UTF_8 + "; q=0.8"),
                                          reqTarget, RoutingStatus.OK, SessionProtocol.H2C);
        final RoutingContext ctx3 =
                new DefaultRoutingContext(virtualHost, "example.com",
                                          RequestHeaders.of(HttpMethod.GET, "/hello",
                                                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                            HttpHeaderNames.ACCEPT,
                                                            MediaType.XML_UTF_8 + ", " +
                                                            MediaType.JSON_UTF_8 + "; q=0.8"),
                                          reqTarget, RoutingStatus.OK, SessionProtocol.H2C);

        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode());
        assertThat(ctx1).isEqualTo(ctx2);
        assertThat(ctx1).isNotEqualTo(ctx3);
    }

    @Test
    void queryDoesNotMatterWhenComparing() {
        final VirtualHost virtualHost = virtualHost();
        final RoutingContext ctx1 = create(virtualHost, "/hello", "a=1&b=1");
        final RoutingContext ctx2 = create(virtualHost, "/hello", "a=1");
        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode());
        assertThat(ctx1).isEqualTo(ctx2);
    }

    @Test
    void hashcodeRecalculateWhenMethodChange() {
        final VirtualHost virtualHost = virtualHost();
        final RequestTarget reqTarget = RequestTarget.forServer("/hello");
        assertThat(reqTarget).isNotNull();

        final RoutingContext ctx1 =
                new DefaultRoutingContext(virtualHost, "example.com",
                                          RequestHeaders.of(HttpMethod.GET, "/hello",
                                                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                            HttpHeaderNames.ACCEPT,
                                                            MediaType.JSON_UTF_8 + ", " +
                                                            MediaType.XML_UTF_8 + "; q=0.8"),
                                          reqTarget, RoutingStatus.OK, SessionProtocol.H2C);
        final RoutingContext ctx2 =
                new DefaultRoutingContext(virtualHost, "example.com",
                                          RequestHeaders.of(HttpMethod.POST, "/hello",
                                                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                            HttpHeaderNames.ACCEPT,
                                                            MediaType.JSON_UTF_8 + ", " +
                                                            MediaType.XML_UTF_8 + "; q=0.8"),
                                          reqTarget, RoutingStatus.OK, SessionProtocol.H2C);
        final RoutingContext ctx3 = ctx1.withMethod(HttpMethod.POST);
        assertThat(ctx1.hashCode()).isNotEqualTo(ctx3.hashCode());
        assertThat(ctx2.hashCode()).isEqualTo(ctx3.hashCode());
    }

    static RoutingContext create(String path) {
        return create(path, null);
    }

    static RoutingContext create(String path, @Nullable String query) {
        return create(virtualHost(), path, query);
    }

    static RoutingContext create(VirtualHost virtualHost, String path, @Nullable String query) {
        final String requestPath = query != null ? path + '?' + query : path;
        final RequestTarget reqTarget = RequestTarget.forServer(requestPath);
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, requestPath);
        assertThat(reqTarget).isNotNull();

        return DefaultRoutingContext.of(virtualHost, "example.com",
                                        reqTarget, headers, RoutingStatus.OK, SessionProtocol.H2C);
    }

    static VirtualHost virtualHost() {
        final HttpService service = mock(HttpService.class);
        when(service.options()).thenReturn(ServiceOptions.of());
        final Server server = Server.builder()
                                    .virtualHost("example.com")
                                    .serviceUnder("/", service)
                                    .and()
                                    .build();
        return server.config().findVirtualHost("example.com", -1);
    }
}
