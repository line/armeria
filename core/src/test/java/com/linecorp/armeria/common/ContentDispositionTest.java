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
/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.ContentDisposition.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContentDisposition}.
 * @author Sebastien Deleuze
 * @author Rossen Stoyanchev
 */
class ContentDispositionTest {

    // Forked from https://github.com/spring-projects/spring-framework/blob/d9ccd618ea9cbf339eb5639d24d5a5fabe8157b5/spring-web/src/test/java/org/springframework/http/ContentDispositionTests.java

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
    void fallbackInvalidCharsetTo_ISO_8859_1() {
        final ContentDisposition contentDisposition =
                parse("form-data; name=\"name\"; filename*=UTF-16''test%A9.txt");
        assertThat(contentDisposition.filename().getBytes(StandardCharsets.ISO_8859_1))
                .isEqualTo("test©.txt".getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void parseEncodedFilenameWithInvalidName() {
        assertThatThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-8''%A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Invalid filename header field parameter format (as defined in RFC 5987): " +
                        "%A (charset: UTF-8)");

        assertThatThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-8''%A.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Invalid filename header field parameter format (as defined in RFC 5987): " +
                        "%A.txt (charset: UTF-8)");
    }

    // gh-23077
    @Test
    void parseWithEscapedQuote() {
        final BiConsumer<String, String> tester = (description, filename) -> {
            assertThat(parse("form-data; name=\"file\"; filename=\"" + filename + '"'))
                    .as(description)
                    .isEqualTo(ContentDisposition.builder("form-data")
                                                 .name("file")
                                                 .filename(filename)
                                                 .build());
        };

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
        assertThat(parse("form-data; name=\"foo\";; ; filename=\"foo.txt\""))
                .isEqualTo(ContentDisposition.builder("form-data")
                                             .name("foo")
                                             .filename("foo.txt")
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
                                     .build().toString())
                .isEqualTo("form-data; name=\"foo\"; filename=\"foo.txt\"");
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
        assertThatThrownBy(() -> {
            ContentDisposition.builder("form-data")
                              .name("name")
                              .filename("test.txt", StandardCharsets.UTF_16);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Charset: UTF-16 (expected: US-ASCII, UTF-8 or ISO-8859-1)");
    }

    @Test
    void testFactory() {
        assertThat(ContentDisposition.of("typeA"))
                .isEqualTo(ContentDisposition.builder("typeA").build());

        assertThat(ContentDisposition.of("typeB", "nameB"))
                .isEqualTo(ContentDisposition.builder("typeB")
                                             .name("nameB")
                                             .build());

        assertThat(ContentDisposition.of("typeC", "nameC", "file.txt"))
                .isEqualTo(ContentDisposition.builder("typeC")
                                             .name("nameC")
                                             .filename("file.txt")
                                             .build());
    }

    @Test
    void available() {
        final boolean res = Charset.availableCharsets().keySet()
                                   .stream().allMatch(Charset::isSupported);
        assertThat(res).isTrue();
    }
}
