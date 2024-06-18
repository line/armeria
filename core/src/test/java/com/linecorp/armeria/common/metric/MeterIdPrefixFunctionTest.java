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

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

class MeterIdPrefixFunctionTest {

    @Test
    void testWithTags() {
        final MeterIdPrefixFunction f = MeterIdPrefixFunction.of(
                (registry, log) -> new MeterIdPrefix("requests_total", "region", "us-west"));

        assertThat(f.withTags("zone", "1a", "host", "foo").completeRequestPrefix(null, null))
                .isEqualTo(new MeterIdPrefix("requests_total",
                                             "region", "us-west", "zone", "1a", "host", "foo"));

        assertThat(f.withTags(Tag.of("zone", "1a"), Tag.of("host", "foo")).completeRequestPrefix(null, null))
                .isEqualTo(new MeterIdPrefix("requests_total",
                                             "region", "us-west", "zone", "1a", "host", "foo"));
    }

    @Test
    void testWithUnzippedTags() {
        final MeterIdPrefixFunction f = MeterIdPrefixFunction.of(
                (registry, log) -> new MeterIdPrefix("requests_total", "region", "us-east"));

        assertThat(f.withTags("host", "bar").completeRequestPrefix(null, null))
                .isEqualTo(new MeterIdPrefix("requests_total", "region", "us-east", "host", "bar"));
    }

    @Test
    void testAndThen() {
        final ServiceRequestContext ctx = newServiceContext(
                HttpMethod.GET, "/", RpcRequest.of(MeterIdPrefixFunctionTest.class, "doFoo"));
        ctx.logBuilder().endResponse();
        final MeterIdPrefixFunction f = new MeterIdPrefixFunction() {
            @Override
            public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
                return new MeterIdPrefix("oof");
            }

            @Override
            public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
                return new MeterIdPrefix("foo", ImmutableList.of());
            }
        };
        final MeterIdPrefixFunction f2 = f.andThen(
                (registry, log, id) -> id.appendWithTags("bar", "log.name", log.name()));
        assertThat(f2.completeRequestPrefix(PrometheusMeterRegistries.newRegistry(),
                                            ctx.log().ensureComplete()))
                .isEqualTo(new MeterIdPrefix("foo.bar", "log.name", "doFoo"));
        assertThat(f2.activeRequestPrefix(PrometheusMeterRegistries.newRegistry(),
                                          ctx.log().ensureRequestComplete()))
                .isEqualTo(new MeterIdPrefix("oof.bar", "log.name", "doFoo"));
    }

    @Test
    void defaultApply() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f = MeterIdPrefixFunction.ofDefault("foo");

        ServiceRequestContext ctx;
        MeterIdPrefix res;

        // A simple HTTP request.
        ctx = newServiceContext(HttpMethod.GET, "/", null);
        ctx.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, ctx.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("http.status", "0"),
                                               Tag.of("method", "GET"),
                                               Tag.of("service", ctx.config().service().getClass().getName()));

        // An RPC request.
        ctx = newServiceContext(HttpMethod.POST, "/post",
                                RpcRequest.of(MeterIdPrefixFunctionTest.class, "doFoo"));
        ctx.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, ctx.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("http.status", "0"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("service", MeterIdPrefixFunctionTest.class.getName()));

        // An RPC request with client context.
        final ClientRequestContext clientCtx = newClientContext(
                HttpMethod.POST, "/post", RpcRequest.of(MeterIdPrefixFunctionTest.class, "doFoo"));
        clientCtx.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, clientCtx.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("http.status", "0"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("service", MeterIdPrefixFunctionTest.class.getName()));

        // HTTP response status.
        ctx = newServiceContext(HttpMethod.GET, "/get", null);
        ctx.logBuilder().startResponse();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, ctx.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("http.status", "200"),
                                               Tag.of("method", "GET"),
                                               Tag.of("service", ctx.config().service().getClass().getName()));
    }

    @Test
    void defaultActiveRequestPrefix() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f = MeterIdPrefixFunction.ofDefault("foo");

        ServiceRequestContext ctx;
        MeterIdPrefix res;

        // A simple HTTP request.
        ctx = newServiceContext(HttpMethod.GET, "/", null);
        res = f.activeRequestPrefix(registry, ctx.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("method", "GET"),
                                               Tag.of("service", ctx.config().service().getClass().getName()));

        // An RPC request.
        ctx = newServiceContext(HttpMethod.POST, "/post",
                                RpcRequest.of(MeterIdPrefixFunctionTest.class, "doFoo"));
        res = f.activeRequestPrefix(registry, ctx.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("service", MeterIdPrefixFunctionTest.class.getName()));

        // An RPC request with client context.
        final ClientRequestContext clientCtx = newClientContext(
                HttpMethod.POST, "/post", RpcRequest.of(MeterIdPrefixFunctionTest.class, "doFoo"));
        res = f.activeRequestPrefix(registry, clientCtx.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("method", "doFoo"),
                                               Tag.of("service", MeterIdPrefixFunctionTest.class.getName()));

        // HTTP response status.
        ctx = newServiceContext(HttpMethod.GET, "/get", null);
        ctx.logBuilder().startResponse();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        res = f.activeRequestPrefix(registry, ctx.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("method", "GET"),
                                               Tag.of("service", ctx.config().service().getClass().getName()));
    }

    @Nested
    class EqualsAndHashCode {
        @Test
        void noTags() {
            final MeterIdPrefix prefix1 = new MeterIdPrefix("name");
            final MeterIdPrefix prefix2 = new MeterIdPrefix("name");
            final MeterIdPrefix prefix3 = new MeterIdPrefix("name2");

            assertThat(prefix1).isEqualTo(prefix2);
            assertThat(prefix1.hashCode()).isEqualTo(prefix2.hashCode());
            assertThat(prefix1).isNotEqualTo(prefix3);
        }

        @Test
        void tagsSameOrder() {
            final MeterIdPrefix prefix1 = new MeterIdPrefix("name", "animal", "cat", "sound", "meow");
            final MeterIdPrefix prefix2 = new MeterIdPrefix("name", "animal", "cat", "sound", "meow");
            final MeterIdPrefix prefix3 = new MeterIdPrefix("name", "animal", "dog", "sound", "bowwow");

            assertThat(prefix1).isEqualTo(prefix2);
            assertThat(prefix1.hashCode()).isEqualTo(prefix2.hashCode());
            assertThat(prefix1).isNotEqualTo(prefix3);
        }

        @Test
        void tagsDifferentOrder() {
            final MeterIdPrefix prefix1 = new MeterIdPrefix("name", "animal", "cat", "sound", "meow");
            final MeterIdPrefix prefix2 = new MeterIdPrefix("name", "sound", "meow", "animal", "cat");

            assertThat(prefix1).isEqualTo(prefix2);
            assertThat(prefix1.hashCode()).isEqualTo(prefix2.hashCode());
        }
    }

    private static ServiceRequestContext newServiceContext(HttpMethod method, String path,
                                                           @Nullable Object requestContent) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(method, path));
        ctx.logBuilder().requestContent(requestContent, null);
        ctx.logBuilder().endRequest();
        return ctx;
    }

    private static ClientRequestContext newClientContext(HttpMethod method, String path,
                                                         @Nullable Object requestContent) {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(method, path));
        ctx.logBuilder().requestContent(requestContent, null);
        ctx.logBuilder().endRequest();
        return ctx;
    }
}
