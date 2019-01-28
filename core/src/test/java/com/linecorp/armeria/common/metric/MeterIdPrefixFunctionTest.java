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

import javax.annotation.Nullable;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

public class MeterIdPrefixFunctionTest {

    @Test
    public void testWithTags() {
        final MeterIdPrefixFunction f =
                (registry, log) -> new MeterIdPrefix("requests_total", "region", "us-west");

        assertThat(f.withTags("zone", "1a", "host", "foo").apply(null, null))
                .isEqualTo(new MeterIdPrefix("requests_total",
                                             "region", "us-west", "zone", "1a", "host", "foo"));
    }

    @Test
    public void testWithUnzippedTags() {
        final MeterIdPrefixFunction f =
                (registry, log) -> new MeterIdPrefix("requests_total", "region", "us-east");

        assertThat(f.withTags("host", "bar").apply(null, null))
                .isEqualTo(new MeterIdPrefix("requests_total", "region", "us-east", "host", "bar"));
    }

    @Test
    public void testAndThen() {
        final MeterIdPrefixFunction f = new MeterIdPrefixFunction() {
            @Override
            public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestLog log) {
                return new MeterIdPrefix("oof");
            }

            @Override
            public MeterIdPrefix apply(MeterRegistry registry, RequestLog log) {
                return new MeterIdPrefix("foo", ImmutableList.of());
            }
        };
        final MeterIdPrefixFunction f2 = f.andThen((registry, id) -> id.append("bar"));
        assertThat(f2.apply(PrometheusMeterRegistries.newRegistry(), null))
                .isEqualTo(new MeterIdPrefix("foo.bar", ImmutableList.of()));
        assertThat(f2.activeRequestPrefix(PrometheusMeterRegistries.newRegistry(), null))
                .isEqualTo(new MeterIdPrefix("oof.bar", ImmutableList.of()));
    }

    @Test
    public void defaultApply() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f = MeterIdPrefixFunction.ofDefault("foo");

        RequestContext ctx;
        MeterIdPrefix res;

        // A simple HTTP request.
        ctx = newContext(HttpMethod.GET, "/", null);
        res = f.apply(registry, ctx.log());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostnamePattern", "*"),
                                               Tag.of("httpStatus", "0"),
                                               Tag.of("method", "GET"),
                                               Tag.of("pathMapping", "exact:/"));

        // An RPC request.
        ctx = newContext(HttpMethod.POST, "/post", RpcRequest.of(Object.class, "doFoo"));
        res = f.apply(registry, ctx.log());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostnamePattern", "*"),
                                               Tag.of("httpStatus", "0"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("pathMapping", "exact:/post"));

        // HTTP response status.
        ctx = newContext(HttpMethod.GET, "/get", null);
        ctx.logBuilder().startResponse();
        ctx.logBuilder().responseHeaders(HttpHeaders.of(200));
        res = f.apply(registry, ctx.log());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostnamePattern", "*"),
                                               Tag.of("httpStatus", "200"),
                                               Tag.of("method", "GET"),
                                               Tag.of("pathMapping", "exact:/get"));
    }

    @Test
    public void defaultActiveRequestPrefix() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f = MeterIdPrefixFunction.ofDefault("foo");

        RequestContext ctx;
        MeterIdPrefix res;

        // A simple HTTP request.
        ctx = newContext(HttpMethod.GET, "/", null);
        res = f.activeRequestPrefix(registry, ctx.log());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostnamePattern", "*"),
                                               Tag.of("method", "GET"),
                                               Tag.of("pathMapping", "exact:/"));

        // An RPC request.
        ctx = newContext(HttpMethod.POST, "/post", RpcRequest.of(Object.class, "doFoo"));
        res = f.activeRequestPrefix(registry, ctx.log());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostnamePattern", "*"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("pathMapping", "exact:/post"));

        // HTTP response status.
        ctx = newContext(HttpMethod.GET, "/get", null);
        ctx.logBuilder().startResponse();
        ctx.logBuilder().responseHeaders(HttpHeaders.of(200));
        res = f.activeRequestPrefix(registry, ctx.log());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostnamePattern", "*"),
                                               Tag.of("method", "GET"),
                                               Tag.of("pathMapping", "exact:/get"));
    }

    private static RequestContext newContext(HttpMethod method, String path, @Nullable Object requestContent) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(method, path));
        ctx.logBuilder().requestContent(requestContent, null);
        ctx.logBuilder().endRequest();
        return ctx;
    }
}
