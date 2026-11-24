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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class AutoIndexTest {

    @Test
    void escapeHtmlInFileName() {
        final String html = AutoIndex.listingToHtml("/", "/", ImmutableList.of(
                "<img src=x onerror=alert(document.domain)>")).toStringUtf8();
        assertThat(html).doesNotContain("<img")
                        .contains("<a href=\"./%3Cimg%20src=x%20onerror=alert(document.domain)%3E\">" +
                                  "&lt;img src=x onerror=alert(document.domain)&gt;</a>");
    }

    @Test
    void preventJavaScriptScheme() {
        final String html = AutoIndex.listingToHtml("/", "/", ImmutableList.of(
                "javascript:alert(document.domain)", "javascript:alert(document.domain)/")).toStringUtf8();
        assertThat(html).doesNotContain("href=\"javascript:")
                        .contains("<li class=\"file\"><a href=\"./javascript:alert(document.domain)\">")
                        .contains("<li class=\"directory\"><a href=\"./javascript:alert(document.domain)/\">");
    }
}
