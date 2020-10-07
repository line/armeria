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
package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

class RequestContextExporterTest {

    private static final AttributeKey<String> ATTR1 =
            AttributeKey.valueOf(RequestContextExporterTest.class, "ATTR1");
    private static final AttributeKey<String> ATTR2 =
            AttributeKey.valueOf(RequestContextExporterTest.class, "ATTR2");
    private static final AttributeKey<Foo> ATTR3 =
            AttributeKey.valueOf(Foo.class, "ATTR3");

    @Test
    void shouldNotExportNullValue() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.setAttr(ATTR1, "1");
        ctx.setAttr(ATTR2, null);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        final RequestContextExporter exporter =
                RequestContextExporter.builder()
                                      .keyPattern("*")
                                      .attr("attrs.attr1", ATTR1)
                                      .attr("attrs.attr2", ATTR2)
                                      .build();

        assertThat(exporter.export(ctx)).containsOnlyKeys(
                BuiltInProperty.CLIENT_IP.key,
                BuiltInProperty.ELAPSED_NANOS.key,
                BuiltInProperty.LOCAL_HOST.key,
                BuiltInProperty.LOCAL_IP.key,
                BuiltInProperty.LOCAL_PORT.key,
                BuiltInProperty.REMOTE_HOST.key,
                BuiltInProperty.REMOTE_IP.key,
                BuiltInProperty.REMOTE_PORT.key,
                BuiltInProperty.REQ_NAME.key,
                BuiltInProperty.REQ_SERVICE_NAME.key,
                BuiltInProperty.REQ_AUTHORITY.key,
                BuiltInProperty.REQ_CONTENT_LENGTH.key,
                BuiltInProperty.REQ_DIRECTION.key,
                BuiltInProperty.REQ_METHOD.key,
                BuiltInProperty.REQ_PATH.key,
                BuiltInProperty.RES_CONTENT_LENGTH.key,
                BuiltInProperty.RES_STATUS_CODE.key,
                BuiltInProperty.REQ_ID.key,
                BuiltInProperty.SCHEME.key,
                "attrs.attr1");
    }

    @Test
    void shouldRepopulateWhenAttributeChanges() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestContextExporter exporter =
                RequestContextExporter.builder()
                                      .attr("attrs.attr1", ATTR1)
                                      .build();

        assertThat(exporter.export(ctx)).doesNotContainKeys("attrs.attr1");

        ctx.setAttr(ATTR1, "foo");
        assertThat(exporter.export(ctx)).containsEntry("attrs.attr1", "foo");

        ctx.setAttr(ATTR1, "bar");
        assertThat(exporter.export(ctx)).containsEntry("attrs.attr1", "bar");

        ctx.setAttr(ATTR1, null);
        assertThat(exporter.export(ctx)).doesNotContainKeys("attrs.attr1");
    }

    @Test
    void shouldUseOwnAttrToStoreInternalState() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext rootCtx = ServiceRequestContext.of(req);
        final RequestContextExporter exporter = RequestContextExporter.builder().build();

        // Create an internal state.
        exporter.export(rootCtx);
        final Object rootState = rootCtx.attr(RequestContextExporter.STATE);
        assertThat(rootState).isNotNull();

        // Create a child context.
        final ClientRequestContext childCtx;
        try (SafeCloseable unused = rootCtx.push()) {
            childCtx = ClientRequestContext.of(req);
        }
        assertThat(childCtx.root()).isSameAs(rootCtx);
        assertThat(childCtx.attr(RequestContextExporter.STATE)).isSameAs(rootState);
        assertThat(childCtx.ownAttr(RequestContextExporter.STATE)).isNull();

        // Make sure a new internal state object is created.
        exporter.export(childCtx);
        final Object childState = childCtx.attr(RequestContextExporter.STATE);
        assertThat(childState).isNotNull().isNotSameAs(rootState);
    }

    @Test
    void shouldExportDifferentAliasOnSameKey() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.setAttr(ATTR1, "1");
        ctx.setAttr(ATTR2, "2");
        final RequestContextExporter exporter =
                RequestContextExporter.builder()
                                      .attr("attrs.attr1-1", ATTR1)
                                      .attr("attrs.attr1-2", ATTR1)
                                      .attr("attrs.attr2", ATTR2)
                                      .build();

        assertThat(exporter.export(ctx)).containsOnlyKeys(
                "attrs.attr1-1",
                "attrs.attr1-2",
                "attrs.attr2");
    }

    @Test
    void customExportKey() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.setAttr(ATTR1, "1");
        ctx.setAttr(ATTR3, new Foo("foo"));
        final RequestContextExporter exporter = RequestContextExporter
                .builder()
                .attr("attrs.attr1", ATTR1)
                .attr("my_attr2", ATTR1)
                .requestHeader(HttpHeaderNames.METHOD, "request_method")
                .keyPattern("request_id=req.id")
                .keyPattern("foo=attr:" + Foo.class.getName() + "#ATTR3")
                .keyPattern("bar=attr:" + Foo.class.getName() + "#ATTR3:" + FooStringifier.class.getName())
                .build();
        final Map<String, String> export;
        try (SafeCloseable ignored = ctx.push()) {
            export = exporter.export();
        }
        assertThat(export).containsOnlyKeys("request_id", "request_method",
                                            "attrs.attr1", "my_attr2",
                                            "foo", "bar");
    }

    static final class Foo {
        final String value;

        Foo(final String value) {
            this.value = value;
        }
    }

    static final class FooStringifier implements Function<Foo, String> {

        @Override
        public String apply(final Foo foo) {
            return foo.value;
        }
    }
}
