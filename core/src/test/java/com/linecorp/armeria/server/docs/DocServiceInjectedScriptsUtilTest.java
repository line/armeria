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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class DocServiceInjectedScriptsUtilTest {

    @ParameterizedTest
    @ValueSource(strings = { "#ff0089", "#ff9dc3", "#3a3"})
    void withTitleBackground_givenValidColor_returnsScriptWithColor(String color) {

        final String result = DocServiceInjectedScriptsUtil.withTitleBackground(color);

        assertThat(result).isNotBlank().contains(color);
    }

    @ParameterizedTest
    @ValueSource(strings = { "#1234567", "#ABCDEFA", "#7654321"})
    void withTitleBackground_givenTooLongColor_throwsException(String color) {

        assertThatThrownBy(() -> DocServiceInjectedScriptsUtil.withGotoBackground(color))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length exceeds");
    }

    @ParameterizedTest
    @ValueSource(strings = { "#12345Z", "#ZABCDE", "#@12345"})
    void withTitleBackground_givenInvalidColor_throwsException(String color) {

        assertThatThrownBy(() -> DocServiceInjectedScriptsUtil.withGotoBackground(color))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in hex format");
    }

    @ParameterizedTest
    @ValueSource(strings = { "#ff0089", "#ff9dc3", "#3a3"})
    void withGotoBackground_givenValidColor_returnsScriptWithColor(String color) {

        final String result = DocServiceInjectedScriptsUtil.withGotoBackground(color);

        assertThat(result).isNotBlank().contains(color);
    }

    @ParameterizedTest
    @MethodSource("getStreamOfValidUri")
    void withFavicon_givenValidUri_returnsScriptWithUri(String uri, String expectedUri) {

        final String result = DocServiceInjectedScriptsUtil.withFavicon(uri);

        assertThat(result).contains(expectedUri).doesNotContain(uri);
    }

    private static Stream<Arguments> getStreamOfValidUri() {
        return Stream.of(
                Arguments.of("https://armeria.dev/static/icon.png", "https:\\/\\/armeria.dev\\/static\\/icon.png"),
                Arguments.of("/images/icon.svg", "\\/images\\/icon.svg")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript://armeria.dev/static/icon.svg",
            "data://line.com/static/icon.svg",
            "https://\\evil.corp/image/icon.svg"
    })
    void withFavicon_givenInvalidUri_throwsException(String uri) {

        assertThatThrownBy(() -> DocServiceInjectedScriptsUtil.withFavicon(uri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uri invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://armeria.dev/static/icon.js",
            "https://line.com/static/icon.zip",
            "https://evil.corp/image/icon.jpeg"
    })
    void withFavicon_givenBadImageExtension_throwsException(String uri) {

        assertThatThrownBy(() -> DocServiceInjectedScriptsUtil.withFavicon(uri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension not allowed");
    }

    @ParameterizedTest
    @MethodSource("getStreamOfEvilUri")
    void withFavicon_givenEvilUri_returnsScriptWithCleanUri(String uri, String expectedUri) {

        final String result = DocServiceInjectedScriptsUtil.withFavicon(uri);

        assertThat(result).contains(expectedUri).doesNotContain(uri);
    }

    private static Stream<Arguments> getStreamOfEvilUri() {
        return Stream.of(
                Arguments.of("https://line.cóm&/static=/'icon.png", "https:\\/\\/line.c\\u00F3m\\u0026\\/static\\u003D\\/\\'icon.png"),
                Arguments.of("';alert(1);//.ico", "\\';alert(1);\\/\\/.ico")
        );
    }
}
