/*
 * Copyright 2026 LY Corporation
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

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

class HeadersSanitizerTest {

    @Test
    void preserveDefaultSensitiveHeaders() {
        assertSanitized(builder -> builder.headersMaskingFunction((name, value) -> {
            assertThat(name.toString()).isEqualTo("x-token");
            return "masked";
        }), HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "auth-secret", "x-token", "token-secret"),
                        "[authorization=****, x-token=masked]",
                        "{\"authorization\":\"****\",\"x-token\":\"masked\"}");
    }

    @Test
    void sensitiveHeadersUseOnlyMaskingFunction() {
        assertSanitized(builder -> builder.sensitiveHeaders("AUTHORIZATION")
                                          .maskingFunction((name, value) -> {
                                              assertThat(name.toString())
                                                      .isEqualTo(HttpHeaderNames.AUTHORIZATION.toString());
                                              return "sensitive";
                                          })
                                          .headersMaskingFunction((name, value) -> {
                                              assertThat(name.toString()).isEqualTo("x-token");
                                              return "other";
                                          }),
                        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "auth-secret",
                                       HttpHeaderNames.AUTHORIZATION, "another-secret",
                                       "x-token", "token-secret"),
                        "[authorization=[sensitive, sensitive], x-token=other]",
                        "{\"authorization\":\"[sensitive, sensitive]\",\"x-token\":\"other\"}");
    }

    @Test
    void emptySensitiveHeadersSkipMaskingFunction() {
        assertSanitized(builder -> builder.sensitiveHeaders(Collections.emptyList())
                                          .maskingFunction((name, value) -> {
                                              throw new AssertionError("maskingFunction must not be invoked");
                                          }),
                        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "unchanged"),
                        "[authorization=unchanged]", "{\"authorization\":\"unchanged\"}");
    }

    @Test
    void emptySensitiveHeadersApplyHeadersMaskingFunctionToAllHeaders() {
        assertSanitized(builder -> builder.sensitiveHeaders(Collections.emptyList())
                                          .maskingFunction((name, value) -> {
                                              throw new AssertionError("maskingFunction must not be invoked");
                                          })
                                          .headersMaskingFunction((name, value) -> "masked"),
                        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "auth-secret",
                                       HttpHeaderNames.PATH, "/?token=path-secret"),
                        "[:path=masked, authorization=masked]",
                        "{\"authorization\":\"masked\",\":path\":\"masked\"}");
    }

    @Test
    void removeIndividualHeaderValues() {
        assertSanitized(builder -> builder.headersMaskingFunction((name, value) ->
                                value.equals("remove") ? null : "masked-" + value),
                        HttpHeaders.builder()
                                   .add("x-token", "first")
                                   .add("x-token", "remove")
                                   .add("x-token", "last")
                                   .add("x-single", "remove")
                                   .add("x-removed", "remove")
                                   .add("x-removed", "remove")
                                   .build(),
                        "[x-token=[masked-first, masked-last]]",
                        "{\"x-token\":\"[masked-first, masked-last]\"}");
    }

    @Test
    void removedSensitiveHeadersDoNotFallThrough() {
        assertSanitized(builder -> builder.sensitiveHeaders("authorization")
                                          .maskingFunction((name, value) -> null)
                                          .headersMaskingFunction((name, value) -> {
                                              assertThat(name.toString()).isEqualTo("x-public");
                                              return value;
                                          }),
                        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "auth-secret",
                                       HttpHeaderNames.AUTHORIZATION, "another-secret",
                                       "x-public", "unchanged"),
                        "[x-public=unchanged]", "{\"x-public\":\"unchanged\"}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void removeAllHeaders(boolean endOfStream) {
        assertSanitized(builder -> builder.sensitiveHeaders(Collections.emptyList())
                                          .headersMaskingFunction((name, value) -> null),
                        HttpHeaders.builder().add("x-secret", "secret").endOfStream(endOfStream).build(),
                        endOfStream ? "[EOS]" : "[]", "{}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void removeAllSensitiveHeaders(boolean endOfStream) {
        assertSanitized(builder -> builder.maskingFunction((name, value) -> null),
                        HttpHeaders.builder().add(HttpHeaderNames.COOKIE, "secret")
                                   .endOfStream(endOfStream).build(),
                        endOfStream ? "[EOS]" : "[]", "{}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void preserveEndOfStream(boolean endOfStream) {
        assertSanitized(builder -> builder.headersMaskingFunction((name, value) -> value),
                        HttpHeaders.builder().add("x-public", "unchanged").endOfStream(endOfStream).build(),
                        endOfStream ? "[EOS, x-public=unchanged]" : "[x-public=unchanged]",
                        "{\"x-public\":\"unchanged\"}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void emptyHeadersDoNotInvokeMaskingFunctions(boolean endOfStream) {
        final HeaderMaskingFunction function = (name, value) -> {
            throw new AssertionError("No header values to mask");
        };
        assertSanitized(builder -> builder.maskingFunction(function).headersMaskingFunction(function),
                        HttpHeaders.builder().endOfStream(endOfStream).build(),
                        endOfStream ? "[EOS]" : "[]", "{}");
    }

    @Test
    void headersMaskingFunctionCanUseDynamicRules() {
        final AtomicReference<String> sensitiveHeader = new AtomicReference<>("x-first");
        final HeaderMaskingFunction function = (name, value) ->
                name.contentEquals(sensitiveHeader.get()) ? "****" : value;
        final HeadersSanitizer<String> text = HeadersSanitizer.builderForText()
                                                            .headersMaskingFunction(function).build();
        final HeadersSanitizer<?> json = HeadersSanitizer.builderForJson()
                                                        .headersMaskingFunction(function).build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpHeaders headers = HttpHeaders.of("x-first", "first-secret", "x-second", "second-secret");
        for (HeadersSanitizer<?> sanitizer : Arrays.asList(text, json)) {
            sensitiveHeader.set("x-first");
            assertThat(String.valueOf(sanitizer.sanitize(ctx, headers)))
                    .doesNotContain("first-secret").contains("second-secret");
            sensitiveHeader.set("x-second");
            assertThat(String.valueOf(sanitizer.sanitize(ctx, headers)))
                    .contains("first-secret").doesNotContain("second-secret");
        }
    }

    @Test
    void rejectNullHeadersMaskingFunction() {
        assertThatThrownBy(() -> HeadersSanitizer.builderForText().headersMaskingFunction(null))
                .isInstanceOf(NullPointerException.class).hasMessage("headersMaskingFunction");
        assertThatThrownBy(() -> HeadersSanitizer.builderForJson().headersMaskingFunction(null))
                .isInstanceOf(NullPointerException.class).hasMessage("headersMaskingFunction");
    }

    private static void assertSanitized(Consumer<AbstractHeadersSanitizerBuilder<?, ?>> configure,
                                        HttpHeaders headers, String expectedText, String expectedJson) {
        final TextHeadersSanitizerBuilder textBuilder = HeadersSanitizer.builderForText();
        final JsonHeadersSanitizerBuilder jsonBuilder = HeadersSanitizer.builderForJson();
        configure.accept(textBuilder);
        configure.accept(jsonBuilder);
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpHeaders original = headers.toBuilder().build();

        assertThat(textBuilder.build().sanitize(ctx, headers)).isEqualTo(expectedText);
        assertThatJson(jsonBuilder.build().sanitize(ctx, headers)).isEqualTo(expectedJson);
        assertThat(headers).isEqualTo(original);
    }
}
