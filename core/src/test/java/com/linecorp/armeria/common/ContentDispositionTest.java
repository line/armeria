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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.ContentDisposition;

/**
 * Unit tests for {@link ContentDisposition}.
 * @author Sebastien Deleuze
 * @author Rossen Stoyanchev
 */
class ContentDispositionTest {

    // Forked from https://github.com/spring-projects/spring-framework/blob/d9ccd618ea9cbf339eb5639d24d5a5fabe8157b5/spring-web/src/test/java/org/springframework/http/ContentDispositionTests.java

    private static final DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;

    private static ContentDisposition parse(String input) {
        return ContentDisposition.parse(input);
    }

    @Test
    void parse() {
        assertThat(parse("form-data; name=\"foo\"; filename=\"foo.txt\"; size=123"))
                .isEqualTo(ContentDisposition.builder("form-data")
                                             .name("foo")
                                             .filename("foo.txt")
                                             .size(123L)
                                             .build());
    }

    @Test
    void parseFilenameUnquoted() {
        assertThat(parse("form-data; filename=unquoted"))
                .isEqualTo(ContentDisposition.builder("form-data")
                                             .filename("unquoted")
                                             .build());
    }

    // SPR-16091
    @Test
    void parseFilenameWithSemicolon() {
        assertThat(parse("attachment; filename=\"filename with ; semicolon.txt\""))
                .isEqualTo(ContentDisposition.builder("attachment")
                                             .filename("filename with ; semicolon.txt")
                                             .build());
    }

    @Test
    void parseEncodedFilename() {
        assertThat(parse("form-data; name=\"name\"; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt"))
                .isEqualTo(ContentDisposition.builder("form-data")
                                             .name("name")
                                             .filename("中文.txt", StandardCharsets.UTF_8)
                                             .build());
    }

    // gh-24112
    @Test
    void parseEncodedFilenameWithPaddedCharset() {
        assertThat(parse("attachment; filename*= UTF-8''some-file.zip"))
                .isEqualTo(ContentDisposition.builder("attachment")
                                             .filename("some-file.zip", StandardCharsets.UTF_8)
                                             .build());
    }

    @Test
    void parseEncodedFilenameWithoutCharset() {
        assertThat(parse("form-data; name=\"name\"; filename*=test.txt"))
                .isEqualTo(ContentDisposition.builder("form-data")
                                             .name("name")
                                             .filename("test.txt")
                                             .build());
    }

    @Test
    void parseEncodedFilenameWithInvalidCharset() {
        assertThatThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-16''test.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Charset should be UTF-8 or ISO-8859-1");
    }

    @Test
    void parseEncodedFilenameWithInvalidName() {
        assertThatThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-8''%A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ContentDisposition.INVALID_HEADER_FIELD_PARAMETER_FORMAT);

        assertThatThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-8''%A.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ContentDisposition.INVALID_HEADER_FIELD_PARAMETER_FORMAT);
    }

    // gh-23077
    @Test
    @SuppressWarnings("deprecation")
    void parseWithEscapedQuote() {
        final BiConsumer<String, String> tester = (description, filename) ->
                assertThat(parse("form-data; name=\"file\"; filename=\"" + filename + "\"; size=123"))
                        .as(description)
                        .isEqualTo(ContentDisposition.builder("form-data")
                                                     .name("file")
                                                     .filename(filename)
                                                     .size(123L)
                                                     .build());

        tester.accept("Escaped quotes should be ignored",
                      "\\\"The Twilight Zone\\\".txt");

        tester.accept("Escaped quotes preceded by escaped backslashes should be ignored",
                      "\\\\\\\"The Twilight Zone\\\\\\\".txt");

        tester.accept("Escaped backslashes should not suppress quote",
                      "The Twilight Zone \\\\");

        tester.accept("Escaped backslashes should not suppress quote",
                      "The Twilight Zone \\\\\\\\");
    }

    @Test
    void parseWithExtraSemicolons() {
        assertThat(parse("form-data; name=\"foo\";; ; filename=\"foo.txt\"; size=123"))
                .isEqualTo(ContentDisposition.builder("form-data")
                                             .name("foo")
                                             .filename("foo.txt")
                                             .size(123L)
                                             .build());
    }

    @Test
    void parseDates() {
        final ZonedDateTime creationTime = ZonedDateTime.parse("Mon, 12 Feb 2007 10:15:30 -0500", formatter);
        final ZonedDateTime modificationTime =
                ZonedDateTime.parse("Tue, 13 Feb 2007 10:15:30 -0500", formatter);
        final ZonedDateTime readTime = ZonedDateTime.parse("Wed, 14 Feb 2007 10:15:30 -0500", formatter);

        assertThat(parse("attachment; " +
                         "creation-date=\"" + creationTime.format(formatter) + "\"; " +
                         "modification-date=\"" + modificationTime.format(formatter) + "\"; " +
                         "read-date=\"" + readTime.format(formatter) + '"')).isEqualTo(
                ContentDisposition.builder("attachment")
                                  .creationDate(creationTime)
                                  .modificationDate(modificationTime)
                                  .readDate(readTime)
                                  .build());
    }

    @Test
    void parseIgnoresInvalidDates() {
        final ZonedDateTime readTime = ZonedDateTime.parse("Wed, 14 Feb 2007 10:15:30 -0500", formatter);

        assertThat(
                parse("attachment; " +
                      "creation-date=\"-1\"; " +
                      "modification-date=\"-1\"; " +
                      "read-date=\"" + readTime.format(formatter) + '"')).isEqualTo(
                ContentDisposition.builder("attachment")
                                  .readDate(readTime)
                                  .build());
    }

    @Test
    void parseEmpty() {
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content-Disposition header must not be empty");
    }

    @Test
    void parseNoType() {
        assertThatThrownBy(() -> parse(";"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content-Disposition header must not be empty");
    }

    @Test
    void parseInvalidParameter() {
        assertThatThrownBy(() -> parse("foo;bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid content disposition format");
    }

    @Test
    void format() {
        assertThat(ContentDisposition.builder("form-data")
                                     .name("foo")
                                     .filename("foo.txt")
                                     .size(123L)
                                     .build().toString())
                .isEqualTo("form-data; name=\"foo\"; filename=\"foo.txt\"; size=123");
    }

    @Test
    void formatWithEncodedFilename() {
        assertThat(ContentDisposition.builder("form-data")
                                     .name("name")
                                     .filename("中文.txt", StandardCharsets.UTF_8)
                                     .build().toString())
                .isEqualTo("form-data; name=\"name\"; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt");
    }

    @Test
    void formatWithEncodedFilenameUsingUsAscii() {
        assertThat(ContentDisposition.builder("form-data")
                                     .name("name")
                                     .filename("test.txt", StandardCharsets.US_ASCII)
                                     .build()
                                     .toString())
                .isEqualTo("form-data; name=\"name\"; filename=\"test.txt\"");
    }

    // gh-24220
    @Test
    void formatWithFilenameWithQuotes() {
        final BiConsumer<String, String> tester = (input, output) -> {
            assertThat(ContentDisposition.builder("form-data")
                                         .filename(input)
                                         .build()
                                         .toString())
                    .isEqualTo("form-data; filename=\"" + output + '"');

            assertThat(ContentDisposition.builder("form-data")
                                         .filename(input, StandardCharsets.US_ASCII)
                                         .build()
                                         .toString())
                    .isEqualTo("form-data; filename=\"" + output + '"');
        };

        String filename = "\"foo.txt";
        tester.accept(filename, '\\' + filename);

        filename = "\\\"foo.txt";
        tester.accept(filename, filename);

        filename = "\\\\\"foo.txt";
        tester.accept(filename, '\\' + filename);

        filename = "\\\\\\\"foo.txt";
        tester.accept(filename, filename);

        filename = "\\\\\\\\\"foo.txt";
        tester.accept(filename, '\\' + filename);

        tester.accept("\"\"foo.txt", "\\\"\\\"foo.txt");
        tester.accept("\"\"\"foo.txt", "\\\"\\\"\\\"foo.txt");

        tester.accept("foo.txt\\", "foo.txt");
        tester.accept("foo.txt\\\\", "foo.txt\\\\");
        tester.accept("foo.txt\\\\\\", "foo.txt\\\\");
    }

    @Test
    void formatWithEncodedFilenameUsingInvalidCharset() {
        assertThatThrownBy(() -> ContentDisposition.builder("form-data")
                                                   .name("name")
                                                   .filename("test.txt",
                                                             StandardCharsets.UTF_16)
                                                   .build()
                                                   .toString())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Charset should be UTF-8 or ISO-8859-1.");
    }
}
