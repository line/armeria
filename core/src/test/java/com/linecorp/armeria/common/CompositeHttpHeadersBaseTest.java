/*
 * Copyright 2023 LINE Corporation
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

import org.junit.jupiter.api.Test;

import io.netty.util.AsciiString;

class CompositeHttpHeadersBaseTest {

    @SuppressWarnings("unchecked")
    @Test
    void constructor_shallowCopy() {
        final HttpHeaders header1 = HttpHeaders.of("k1", "v1");
        final HttpHeaders header2 = HttpHeaders.of("k2", "v2");
        final CompositeHttpHeadersBase shallowCopy = new CompositeHttpHeadersBase(header1, header2);
        assertThat(shallowCopy.get("k1")).isEqualTo("v1");
        assertThat(shallowCopy.get("k2")).isEqualTo("v2");

        ((StringMultimap<CharSequence, AsciiString>) header1).remove("k1");
        assertThat(shallowCopy.get("k1")).isNull();
        assertThat(shallowCopy.get("k2")).isEqualTo("v2");

        ((StringMultimap<CharSequence, AsciiString>) header2).clear();
        assertThat(shallowCopy.isEmpty()).isTrue();
    }

    @Test
    void constructor_deepCopy() {
        final HttpHeadersBuilder builder1 = HttpHeaders.builder().add("k1", "v1");
        final HttpHeadersBuilder builder2 = HttpHeaders.builder().add("k2", "v2");
        final CompositeHttpHeadersBase deepCopy = new CompositeHttpHeadersBase(builder1, builder2);
        assertThat(deepCopy.get("k1")).isEqualTo("v1");
        assertThat(deepCopy.get("k2")).isEqualTo("v2");

        builder1.remove("k1");
        assertThat(deepCopy.get("k1")).isEqualTo("v1");
        assertThat(deepCopy.get("k2")).isEqualTo("v2");

        builder2.remove("k2");
        assertThat(deepCopy.get("k1")).isEqualTo("v1");
        assertThat(deepCopy.get("k2")).isEqualTo("v2");
    }

    @Test
    void contentLength() {
        final CompositeHttpHeadersBase headers = new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(headers.isContentLengthUnknown()).isFalse();
        assertThat(headers.contentLength()).isEqualTo(-1);
        assertThat(headers.get(HttpHeaderNames.CONTENT_LENGTH)).isNull();

        headers.contentLength(0);
        assertThat(headers.isContentLengthUnknown()).isFalse();
        assertThat(headers.contentLength()).isEqualTo(0);
        assertThat(headers.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("0");

        headers.contentLength(100);
        assertThat(headers.isContentLengthUnknown()).isFalse();
        assertThat(headers.contentLength()).isEqualTo(100);
        assertThat(headers.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("100");

        headers.contentLengthUnknown();
        assertThat(headers.isContentLengthUnknown()).isTrue();
        assertThat(headers.contentLength()).isEqualTo(-1);
        assertThat(headers.get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
    }

    @Test
    void contentLengths() {
        final CompositeHttpHeadersBase allContentLengthKnown =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.builder().contentLength(100).build(),
                                             HttpHeaders.builder().contentLength(200).build(),
                                             HttpHeaders.builder().contentLength(300).build(),
                                             HttpHeaders.of());
        assertThat(allContentLengthKnown.isContentLengthUnknown()).isFalse();
        assertThat(allContentLengthKnown.contentLength()).isEqualTo(600);

        final CompositeHttpHeadersBase someContentLengthUnknown =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.builder().contentLength(100).build(),
                                             HttpHeaders.builder().contentLengthUnknown().build(),
                                             HttpHeaders.builder().contentLength(200).build(),
                                             HttpHeaders.builder().contentLength(300).build(),
                                             HttpHeaders.of());
        assertThat(someContentLengthUnknown.isContentLengthUnknown()).isTrue();
        assertThat(someContentLengthUnknown.contentLength()).isEqualTo(-1);
    }

    @Test
    void contentType() {
        final CompositeHttpHeadersBase headers = new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(headers.contentType()).isNull();
        assertThat(headers.get(HttpHeaderNames.CONTENT_TYPE)).isNull();

        headers.contentType(MediaType.PNG);
        assertThat(headers.contentType()).isEqualTo(MediaType.PNG);
        assertThat(headers.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.PNG.toString());

        headers.contentType(MediaType.JPEG);
        assertThat(headers.contentType()).isEqualTo(MediaType.JPEG);
        assertThat(headers.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.JPEG.toString());
    }

    @Test
    void contentTypes() {
        final CompositeHttpHeadersBase contentTypes =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.builder().contentType(MediaType.PNG).build(),
                                             HttpHeaders.builder().contentType(MediaType.JPEG).build(),
                                             HttpHeaders.builder().contentType(MediaType.GIF).build(),
                                             HttpHeaders.of());
        assertThat(contentTypes.contentType()).isEqualTo(MediaType.PNG);
        assertThat(contentTypes.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.PNG.toString());

        final CompositeHttpHeadersBase unknown = new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(unknown.contentType()).isNull();
        assertThat(unknown.get(HttpHeaderNames.CONTENT_TYPE)).isNull();
    }

    @Test
    void contentDisposition() {
        final CompositeHttpHeadersBase headers = new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(headers.contentDisposition()).isNull();
        assertThat(headers.get(HttpHeaderNames.CONTENT_DISPOSITION)).isNull();

        final ContentDisposition typeA = ContentDisposition.of("typeA");
        headers.contentDisposition(typeA);
        assertThat(headers.contentDisposition()).isEqualTo(typeA);
        assertThat(headers.get(HttpHeaderNames.CONTENT_DISPOSITION)).isEqualTo(typeA.toString());

        final ContentDisposition typeB = ContentDisposition.of("typeB");
        headers.contentDisposition(typeB);
        assertThat(headers.contentDisposition()).isEqualTo(typeB);
        assertThat(headers.get(HttpHeaderNames.CONTENT_DISPOSITION)).isEqualTo(typeB.toString());
    }

    @Test
    void contentDispositions() {
        final ContentDisposition typeA = ContentDisposition.of("typeA");
        final ContentDisposition typeB = ContentDisposition.of("typeB");
        final CompositeHttpHeadersBase contentDispositions =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.builder().contentDisposition(typeA).build(),
                                             HttpHeaders.builder().contentDisposition(typeB).build(),
                                             HttpHeaders.of());
        assertThat(contentDispositions.contentDisposition()).isEqualTo(typeA);
        assertThat(contentDispositions.get(HttpHeaderNames.CONTENT_DISPOSITION)).isEqualTo(typeA.toString());

        final CompositeHttpHeadersBase unknown = new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(unknown.contentDisposition()).isNull();
        assertThat(unknown.get(HttpHeaderNames.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void endOfStream() {
        final CompositeHttpHeadersBase headers = new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(headers.isEndOfStream()).isFalse();

        headers.endOfStream(true);
        assertThat(headers.isEndOfStream()).isTrue();

        headers.endOfStream(false);
        assertThat(headers.isEndOfStream()).isFalse();
    }

    @Test
    void endOfStreams() {
        final CompositeHttpHeadersBase endOfStreams =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.builder().endOfStream(true).build(),
                                             HttpHeaders.builder().endOfStream(false).build(),
                                             HttpHeaders.of());
        assertThat(endOfStreams.isEndOfStream()).isTrue();

        final CompositeHttpHeadersBase unknown = new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(unknown.isEndOfStream()).isFalse();
    }

    @Test
    void equals_true() {
        final CompositeHttpHeadersBase headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.equals(headers)).isTrue();

        final CompositeHttpHeadersBase other1 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.builder()
                                                        .add("dup", "dup1")
                                                        .endOfStream(false),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.equals(other1)).isTrue();

        final CompositeHttpHeadersBase other2 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("diff1", "diff1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("diff2", "diff2"),
                                             HttpHeaders.of("dup", "dup3"),
                                             HttpHeaders.of("dup", "dup3"),
                                             HttpHeaders.of());
        other2.remove("diff1");
        other2.remove("diff2");
        other2.add("k4", "v4");
        other2.add("k5", "v5");
        other2.add("k6", "v6");
        other2.remove("dup");
        other2.add("dup", "dup1");
        other2.add("dup", "dup2");
        assertThat(headers.equals(other2)).isTrue();
    }

    @Test
    void equals_false() {
        final CompositeHttpHeadersBase headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of());
        assertThat(headers.equals(null)).isFalse();

        final CompositeHttpHeadersBase other1 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k3", "v3"),
                                             HttpHeaders.of());
        assertThat(headers.equals(other1)).isFalse();

        final CompositeHttpHeadersBase other2 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of());
        other2.endOfStream(true);
        assertThat(headers.equals(other2)).isFalse();

        final CompositeHttpHeadersBase other3 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.builder().add("k1", "v1").build(),
                                             HttpHeaders.builder()
                                                        .add("k2", "v2")
                                                        .endOfStream(true)
                                                        .build(),
                                             HttpHeaders.builder().add("k3", "v3").build(),
                                             HttpHeaders.of());
        assertThat(headers.equals(other3)).isFalse();
    }

    @Test
    void testToString() {
        final CompositeHttpHeadersBase headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.toString())
                .isEqualTo("[k1=v1, k2=v2, k3=v3, k4=v4, k5=v5, k6=v6, dup=dup1, dup=dup2]");

        headers.endOfStream(true);
        assertThat(headers.toString())
                .isEqualTo("[EOS, k1=v1, k2=v2, k3=v3, k4=v4, k5=v5, k6=v6, dup=dup1, dup=dup2]");

        headers.clear();
        headers.endOfStream(false);
        assertThat(headers.toString()).isEqualTo("[]");
        assertThat(new CompositeHttpHeadersBase(HttpHeaders.of()).toString()).isEqualTo("[]");

        headers.endOfStream(true);
        assertThat(headers.toString()).isEqualTo("[EOS]");
        assertThat(new CompositeHttpHeadersBase(HttpHeaders.of().toBuilder().endOfStream(true)).toString())
                .isEqualTo("[EOS]");
    }
}
