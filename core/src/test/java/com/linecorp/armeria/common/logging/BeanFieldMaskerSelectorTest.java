/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.SimpleFoo;

class BeanFieldMaskerSelectorTest {

    @Test
    void nullMasker() {
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ContentSanitizer<String> contentSanitizer =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> null)
                                .buildForText();
        assertThatThrownBy(() -> contentSanitizer.apply(ctx, new SimpleFoo()))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(JsonMappingException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".fieldMasker() returned null for");
    }

    @Test
    void fallthroughMasker() {
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Set<BeanFieldInfo> acc = new HashSet<>();
        final Set<BeanFieldInfo> acc2 = new HashSet<>();
        final ContentSanitizer<String> contentSanitizer =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> {
                                    acc.add(info);
                                    return FieldMasker.fallthrough();
                                })
                                .fieldMaskerSelector(((BeanFieldMaskerSelector) info -> {
                                    acc2.add(info);
                                    return FieldMasker.fallthrough();
                                }).orElse(info -> FieldMasker.nullify()))
                                .buildForText();
        final String res = contentSanitizer.apply(ctx, new SimpleFoo());
        assertThatJson(res).isEqualTo("{\"inner\":null}");
        assertThat(acc).isEqualTo(acc2);
        assertThat(acc).isNotEmpty();
    }

    @Test
    void noMaskMasker() {
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ContentSanitizer<String> contentSanitizer =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> FieldMasker.noMask())
                                .buildForText();
        final String res = contentSanitizer.apply(ctx, new SimpleFoo());
        assertThatJson(res).isEqualTo("{\"inner\":{\"hello\":\"world\",\"intVal\":42,\"masked\":\"masked\"}}");
    }

    @Test
    void nullifyMasker() {
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ContentSanitizer<String> contentSanitizer =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> FieldMasker.nullify())
                                .buildForText();
        final String res = contentSanitizer.apply(ctx, new SimpleFoo());
        assertThatJson(res).isEqualTo("{\"inner\":null}");
    }

    @Test
    void contextIsPropagated() {
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Map<String, RequestContext> contexts = new HashMap<>();
        final ContentSanitizer<String> contentSanitizer =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> new FieldMasker() {
                                    @Override
                                    public Object mask(@Nullable RequestContext ctx, Object obj) {
                                        contexts.put(info.name(), ctx);
                                        return obj;
                                    }

                                    @Override
                                    public @Nullable Object mask(Object obj) {
                                        throw new UnsupportedOperationException();
                                    }
                                })
                                .buildForText();
        final String res = contentSanitizer.apply(ctx, new SimpleFoo());
        assertThatJson(res).isEqualTo("{\"inner\":{\"hello\":\"world\",\"intVal\":42,\"masked\":\"masked\"}}");
        assertThat(contexts).containsOnlyKeys("inner", "hello", "intVal", "masked");
        assertThat(contexts.values()).containsOnly(ctx);
    }

    @Test
    void contextIsPropagatedJson() {
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Map<String, RequestContext> contexts = new HashMap<>();
        final ContentSanitizer<JsonNode> contentSanitizer =
                ContentSanitizer.builder()
                                .fieldMaskerSelector((BeanFieldMaskerSelector) info -> new FieldMasker() {
                                    @Override
                                    public Object mask(@Nullable RequestContext ctx, Object obj) {
                                        contexts.put(info.name(), ctx);
                                        return obj;
                                    }

                                    @Override
                                    public @Nullable Object mask(Object obj) {
                                        throw new UnsupportedOperationException();
                                    }
                                })
                                .buildForJson();
        final String res = contentSanitizer.apply(ctx, new SimpleFoo()).toString();
        assertThatJson(res).isEqualTo("{\"inner\":{\"hello\":\"world\",\"intVal\":42,\"masked\":\"masked\"}}");
        assertThat(contexts).containsOnlyKeys("inner", "hello", "intVal", "masked");
        assertThat(contexts.values()).containsOnly(ctx);
    }
}
