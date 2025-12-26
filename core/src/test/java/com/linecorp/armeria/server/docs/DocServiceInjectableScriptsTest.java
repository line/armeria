/*
 *  Copyright 2025 LY Corporation
 *
 *  LY Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server.docs;

import static com.linecorp.armeria.server.docs.DocServiceInjectableScripts.favicon;
import static com.linecorp.armeria.server.docs.DocServiceInjectableScripts.gotoBackground;
import static com.linecorp.armeria.server.docs.DocServiceInjectableScripts.titleBackground;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DocServiceInjectableScriptsTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/annotated", new Object() {
                @Get("/")
                public String index() {
                    return "index";
                }
            });
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .webAppTitle("my-title")
                                      .injectedScripts(titleBackground("0000ff"), // blue
                                                       gotoBackground("ff0000"), // red
                                                       favicon("https://github.com/favicon.ico"))
                                      .build());
        }
    };

    @Test
    void specification() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }

        final String content = server.blockingWebClient().get("/docs/specification.json").contentUtf8();
        assertThatJson(content).node("docServiceExtraInfo.webAppTitle").isStringEqualTo("my-title");
    }

    @ParameterizedTest
    @ValueSource(strings = { "#ff0089", "ff9dc3", "#3a3"})
    void titleBackground_givenValidColor_returnsScriptWithColor(String color) {

        final String result = titleBackground(color);

        assertThat(result).isNotBlank().contains(color);
    }

    @ParameterizedTest
    @ValueSource(strings = { "#1234567", "#ABCDEFA", "#7654321"})
    void titleBackground_givenTooLongColor_throwsException(String color) {

        assertThatThrownBy(() -> titleBackground(color))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length exceeds");
    }

    @ParameterizedTest
    @ValueSource(strings = { "#12345Z", "#ZABCDE", "#@12345"})
    void titleBackground_givenInvalidColor_throwsException(String color) {

        assertThatThrownBy(() -> titleBackground(color))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in hex format");
    }

    @ParameterizedTest
    @ValueSource(strings = { "#ff0089", "ff9dc3", "#3a3"})
    void gotoBackground_givenValidColor_returnsScriptWithColor(String color) {

        final String result = gotoBackground(color);

        assertThat(result).isNotBlank().contains(color);
    }

    @ParameterizedTest
    @MethodSource("getStreamOfValidUri")
    void favicon_givenValidUri_returnsScriptWithUri(String uri, String expectedUri) {

        final String result = favicon(uri);

        assertThat(result).contains(expectedUri).doesNotContain(uri);
    }

    private static Stream<Arguments> getStreamOfValidUri() {
        return Stream.of(
                Arguments.of("https://armeria.dev/static/icon.png", "https:\\/\\/armeria.dev\\/static\\/icon.png"),
                Arguments.of("/images/icon.svg", "\\/images\\/icon.svg")
        );
    }

    @ParameterizedTest
    @MethodSource("getStreamOfEvilUri")
    void favicon_givenEvilUri_returnsScriptWithCleanUri(String uri, String expectedUri) {

        final String result = favicon(uri);

        assertThat(result).contains(expectedUri).doesNotContain(uri);
    }

    private static Stream<Arguments> getStreamOfEvilUri() {
        return Stream.of(
                Arguments.of("https://line.cóm&/static=/'icon.png", "https:\\/\\/line.c\\u00F3m\\u0026\\/static\\u003D\\/\\'icon.png"),
                Arguments.of("';alert(1);//.ico", "\\';alert(1);\\/\\/.ico")
        );
    }
}
