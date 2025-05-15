/*
 *  Copyright 2025 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
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

import org.junit.jupiter.api.Test;

public class DocServiceInjectedScriptsUtilTest {

    @Test
    void withTitleBackground_givenValidColor_returnsScriptWithColor() {

        final String color = "#ff0089";
        final String absentColor = "#ff9dc3";

        final String result = DocServiceInjectedScriptsUtil.withTitleBackground(color);

        assertThat(result).contains(color).doesNotContain(absentColor);
    }

    @Test
    void withTitleBackground_givenInvalidColor_throwsException() {

        final String color = "#1234567";

        assertThatThrownBy(() -> DocServiceInjectedScriptsUtil.withGotoBackground(color))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length exceeds");
    }

    @Test
    void withGotoBackground_givenValidColor_returnsScriptWithColor() {

        final String color = "#ff9dc3";
        final String absentColor = "#ff0089";

        final String result = DocServiceInjectedScriptsUtil.withGotoBackground(color);

        assertThat(result).contains(color).doesNotContain(absentColor);
    }

    @Test
    void withFavicon_givenValidUrl_returnsScriptWithUrl() {

        final String url = "https://armeria.dev/static/icon.png";
        final String absentUrl = "https://line.com/static/icon.png";

        final String result = DocServiceInjectedScriptsUtil.withFavicon(url);

        assertThat(result).contains(url).doesNotContain(absentUrl);
    }

    @Test
    void withFavicon_givenInvalidUrl_throwsException() {

        final String url = "https://armeria.dev/static/icon.js";

        assertThatThrownBy(() -> DocServiceInjectedScriptsUtil.withFavicon(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension not allowed");
    }

    @Test
    void withFavicon_givenEvilUrl_returnsScriptWithUrl() {

        final String url = "https://line.com/static/ico\\n';mal\"ici;ous\n\r.png";
        final String expectedUrl = "https://line.com/static/ico\\\\n\\'mal\\\"icious.png";

        final String result = DocServiceInjectedScriptsUtil.withFavicon(url);

        assertThat(result).contains(expectedUrl).doesNotContain(url);
    }
}
