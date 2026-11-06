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
package com.linecorp.armeria.common.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerSentEventTest {

    @ParameterizedTest
    @ValueSource(strings = { "\n", "\r\n", "\r" })
    void parsesFields(String newline) {
        final String raw = String.join(newline, ": first", ":second", "id: 42", "event: update",
                                       "retry: 1500", "data: hello", "data:world");
        assertThat(ServerSentEvent.parse(raw)).isEqualTo(
                ServerSentEvent.builder().id("42").event("update").retry(Duration.ofMillis(1500))
                               .comment("first\nsecond").data("hello\nworld").build());
    }

    @Test
    void preservesEmptyValuesAndOnlyRemovesOneSpace() {
        assertThat(ServerSentEvent.parse("id\nevent:\ndata\ndata:  leading\ndata:\n:\n"))
                .isEqualTo(ServerSentEvent.builder().id("").event("").data("\n leading\n").comment("")
                                         .build());
        assertThat(ServerSentEvent.parse("data:\tvalue").data()).isEqualTo("\tvalue");
        assertThat(ServerSentEvent.parse("data: a:b:c").data()).isEqualTo("a:b:c");
    }

    @Test
    void distinguishesAbsentAndEmptyTextFields() {
        assertThat(ServerSentEvent.parse("id: 1")).isEqualTo(ServerSentEvent.ofId("1"));
        assertThat(ServerSentEvent.parse("data")).isEqualTo(ServerSentEvent.ofData(""));
        assertThat(ServerSentEvent.parse(":")).isEqualTo(ServerSentEvent.ofComment(""));
    }

    @Test
    void lastValidFieldWins() {
        assertThat(ServerSentEvent.parse("id: old\nid: new\nid: bad\u0000id\nevent: old\nevent: new\n" +
                                         "retry: 1\nretry: 0002\nretry: invalid"))
                .isEqualTo(ServerSentEvent.builder().id("new").event("new").retry(Duration.ofMillis(2))
                                         .build());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "-1", "+1", "1.0", " 1", "1 ", "１２", "9223372036854775808" })
    void ignoresInvalidRetry(String retry) {
        assertThat(ServerSentEvent.parse("retry: 123\nretry: " + retry).retry())
                .isEqualTo(Duration.ofMillis(123));
    }

    @Test
    void acceptsRetryRange() {
        assertThat(ServerSentEvent.parse("retry: 0").retry()).isEqualTo(Duration.ZERO);
        assertThat(ServerSentEvent.parse("retry: 9223372036854775807").retry())
                .isEqualTo(Duration.ofMillis(Long.MAX_VALUE));
    }

    @Test
    void ignoresUnknownFieldsAndStopsAtEventBoundary() {
        assertThat(ServerSentEvent.parse("Data: ignored\nunknown: value\n data: ignored"))
                .isSameAs(ServerSentEvent.empty());
        assertThat(ServerSentEvent.parse("data: first\n\ndata: second\n\n"))
                .isEqualTo(ServerSentEvent.ofData("first"));
        assertThat(new ServerSentEventParser("data: first\n\ndata: second\n\n", true).parse())
                .isEqualTo(ServerSentEvent.ofData("first"));
        assertThat(new ServerSentEventParser("data: first\n\ndata: second\n\n", false).parse())
                .isEqualTo(ServerSentEvent.ofData("first"));
        assertThat(ServerSentEvent.parse("\ndata: second\n\n")).isEqualTo(ServerSentEvent.ofData("second"));
        assertThat(ServerSentEvent.parse("\uFEFF\ndata: second\n\n"))
                .isEqualTo(ServerSentEvent.ofData("second"));
        assertThat(ServerSentEvent.parse("")).isSameAs(ServerSentEvent.empty());
    }

    @Test
    void ignoresOneLeadingBom() {
        assertThat(ServerSentEvent.parse("\uFEFFdata: hello")).isEqualTo(ServerSentEvent.ofData("hello"));
        assertThat(ServerSentEvent.parse("data: \uFEFFhello").data()).isEqualTo("\uFEFFhello");
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> ServerSentEvent.parse(null));
    }
}
