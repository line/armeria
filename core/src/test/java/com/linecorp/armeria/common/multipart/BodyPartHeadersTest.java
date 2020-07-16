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
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.MediaType;

class BodyPartHeadersTest {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/test/java/io/helidon/media/multipart/BodyPartHeadersTest.java

    @Test
    void testHeaderNameCaseInsensitive() {
        final BodyPartHeaders headers = BodyPartHeaders.builder()
                                                       .set("content-type", "text/plain")
                                                       .set("Content-ID", "test")
                                                       .add("my-header", "abc=def; blah; key=value")
                                                       .add("My-header", "foo=bar")
                                                       .build();

        assertThat(headers.get("Content-Type")).isEqualTo("text/plain");
        assertThat(headers.get("Content-Id")).isEqualTo("test");
        assertThat(headers.getAll("my-header")).containsExactly("abc=def; blah; key=value", "foo=bar");
    }

    @Test
    void testContentType() {
        final BodyPartHeaders headers = BodyPartHeaders.builder()
                                                       .set("content-type", "application/json")
                                                       .build();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.JSON);
    }

    @Test
    void testBuilderWithContentType() {
        final BodyPartHeaders headers = BodyPartHeaders.builder()
                                                       .contentType(MediaType.JSON)
                                                       .build();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.JSON);
    }

    @Test
    void testDefaultContentType() {
        final BodyPartHeaders headers = BodyPartHeaders.builder().build();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.PLAIN_TEXT);
    }

    @Test
    void testDefaultContentTypeForFile() {
        final BodyPartHeaders headers = BodyPartHeaders.builder()
                                                       .set("Content-Disposition", "form-data; filename=foo")
                                                       .build();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.OCTET_STREAM);
    }

    @Test
    void testContentDisposition() {
        final BodyPartHeaders headers = BodyPartHeaders.builder()
                                                       .set("Content-Disposition", "form-data; name=foo")
                                                       .build();
        final ContentDisposition cd = headers.contentDisposition();
        assertThat(cd.type()).isEqualTo("form-data");
        assertThat(cd.name()).isEqualTo("foo");
    }
}
