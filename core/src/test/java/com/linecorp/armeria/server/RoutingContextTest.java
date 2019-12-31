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

import static com.linecorp.armeria.server.DefaultRoutingContext.compareMediaType;
import static com.linecorp.armeria.server.DefaultRoutingContext.extractAcceptTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

class RoutingContextTest {

    @Test
    void testAcceptTypes() {
        final HttpHeaders headers = HttpHeaders.of(
                HttpHeaderNames.ACCEPT,
                "application/xml;q=0.9, " +
                "*/*;q=0.8, " +
                "text/html;charset=UTF-8, " +
                "application/xhtml+xml;charset=utf-8");
        final List<MediaType> acceptTypes = extractAcceptTypes(headers);
        assertThat(acceptTypes).containsExactly(MediaType.XHTML_UTF_8,
                                                MediaType.HTML_UTF_8,
                                                MediaType.parse("application/xml;q=0.9"),
                                                MediaType.parse("*/*;q=0.8"));
    }

    @Test
    void testCompareMediaTypes() {
        // Sort by their quality factor.
        assertThat(compareMediaType(MediaType.parse("application/octet-stream;q=0.8"),
                                    MediaType.parse("text/plain;q=0.9")))
                .isGreaterThan(0);
        // Sort by their coverage. (the number of wildcards)
        assertThat(compareMediaType(MediaType.parse("text/*;q=0.9"),
                                    MediaType.parse("text/plain;q=0.9")))
                .isGreaterThan(0);
        // Sort by lexicographic order.
        assertThat(compareMediaType(MediaType.parse("text/plain;q=0.9"),
                                    MediaType.parse("application/octet-stream;q=0.9")))
                .isGreaterThan(0);
    }

    @Test
    void testEquals() {
        final VirtualHost virtualHost = virtualHost();

        RoutingContext ctx1;
        RoutingContext ctx2;
        final RoutingContext ctx3;

        ctx1 = new DefaultRoutingContext(virtualHost, "example.com",
                                         RequestHeaders.of(HttpMethod.GET, "/hello",
                                                           HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                           HttpHeaderNames.ACCEPT,
                                                           MediaType.JSON_UTF_8 + ", " +
                                                           MediaType.XML_UTF_8 + "; q=0.8"),
                                         "/hello", null, false);
        ctx2 = new DefaultRoutingContext(virtualHost, "example.com",
                                         RequestHeaders.of(HttpMethod.GET, "/hello",
                                                           HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                           HttpHeaderNames.ACCEPT,
                                                           MediaType.JSON_UTF_8 + ", " +
                                                           MediaType.XML_UTF_8 + "; q=0.8"),
                                         "/hello", null, false);
        ctx3 = new DefaultRoutingContext(virtualHost, "example.com",
                                         RequestHeaders.of(HttpMethod.GET, "/hello",
                                                           HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                                           HttpHeaderNames.ACCEPT,
                                                           MediaType.XML_UTF_8 + ", " +
                                                           MediaType.JSON_UTF_8 + "; q=0.8"),
                                         "/hello", null, false);

        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode());
        assertThat(ctx1).isEqualTo(ctx2);
        assertThat(ctx1).isNotEqualTo(ctx3);

        ctx1 = new DefaultRoutingContext(virtualHost, "example.com",
                                         RequestHeaders.of(HttpMethod.GET, "/hello"),
                                         "/hello", "a=1&b=1", false);
        ctx2 = new DefaultRoutingContext(virtualHost, "example.com",
                                         RequestHeaders.of(HttpMethod.GET, "/hello"), "/hello", "a=1", false);

        // Queries are different.
        assertThat(ctx1.hashCode()).isNotEqualTo(ctx2.hashCode());
        assertThat(ctx1).isNotEqualTo(ctx2);
    }

    static RoutingContext create(String path) {
        return create(path, null);
    }

    static RoutingContext create(String path, @Nullable String query) {
        final String requestPath = query != null ? path + '?' + query : path;
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, requestPath);
        return DefaultRoutingContext.of(virtualHost(), "example.com",
                                        path, query, headers, false);
    }

    static VirtualHost virtualHost() {
        final HttpService service = mock(HttpService.class);
        final Server server = Server.builder()
                                    .virtualHost("example.com")
                                    .serviceUnder("/", service)
                                    .and()
                                    .build();
        return server.config().findVirtualHost("example.com");
    }
}
