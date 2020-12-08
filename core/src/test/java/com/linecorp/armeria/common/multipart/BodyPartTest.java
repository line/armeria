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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.StreamMessage;

import reactor.core.publisher.Flux;

/**
 * Tests {@link BodyPart}.
 */
class BodyPartTest {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/test/java/io/helidon/media/multipart/BodyPartTest.java

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    @Test
    void testDefaultContentType() {
        final BodyPart bodyPart = BodyPart.builder()
                                          .headers(HttpHeaders.of())
                                          .content("Hello")
                                          .build();
        final HttpHeaders headers = bodyPart.headers();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.PLAIN_TEXT);
    }

    @Test
    void testDefaultContentTypeForFile() {
        final BodyPart bodyPart =
                BodyPart.builder()
                        .headers(HttpHeaders.builder()
                                            .set("Content-Disposition", "form-data; filename=foo")
                                            .build())
                        .content("Hello")
                        .build();
        final HttpHeaders headers = bodyPart.headers();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.OCTET_STREAM);
    }

    @Test
    void testContentFromPublisher() {
        final BodyPart bodyPart =
                BodyPart.builder()
                        .content(StreamMessage.of(HttpData.of(DEFAULT_CHARSET, "body part data")))
                        .build();
        assertThat(getContents(bodyPart)).containsExactly("body part data");
    }

    private static List<String> getContents(BodyPart bodyPart) {
        return Flux.from(bodyPart.content()).map(HttpData::toStringUtf8).collectList().block();
    }

    @Test
    void testContentFromEntity() throws Exception {
        final BodyPart bodyPart = BodyPart.builder()
                                          .content("body part data")
                                          .build();
        assertThat(getContents(bodyPart)).containsExactly("body part data");
    }

    @Test
    void testBuildingPartWithNoContent() {
        assertThatThrownBy(() -> BodyPart.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one content");
    }

    @Test
    void testName() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentDisposition(ContentDisposition.builder("form-data")
                                                                                     .name("foo")
                                                                                     .build())
                                               .build();
        final BodyPart bodyPart = BodyPart.builder()
                                          .headers(headers)
                                          .content("abc")
                                          .build();

        assertThat(bodyPart.headers().contentDisposition().type()).isEqualTo("form-data");
        assertThat(bodyPart.name()).isEqualTo("foo");
        assertThat(bodyPart.filename()).isNull();
    }

    @Test
    void testFilename() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentDisposition(ContentDisposition
                                                                           .builder("attachment")
                                                                           .filename("foo.txt")
                                                                           .build())
                                               .build();
        final BodyPart bodyPart = BodyPart.builder()
                                          .headers(headers)
                                          .content("abc")
                                          .build();

        assertThat(bodyPart.filename()).isEqualTo("foo.txt");
        assertThat(bodyPart.name()).isNull();
    }
}
