/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.MediaType;

public class DefaultMediaTypeResolverTest {

    private static final DefaultMediaTypeResolver RESOLVER = DefaultMediaTypeResolver.INSTANCE;

    @Test
    public void knownExtensions() {
        assertThat(MediaType.AVIF.is(RESOLVER.guessFromPath("image.avif"))).isTrue();
        assertThat(MediaType.HEIF.is(RESOLVER.guessFromPath("image.heif"))).isTrue();
        assertThat(MediaType.MANIFEST_JSON_UTF_8.is(RESOLVER.guessFromPath("app.webmanifest"))).isTrue();
        assertThat(MediaType.PNG.is(RESOLVER.guessFromPath("image.png"))).isTrue();
        assertThat(MediaType.PNG.is(RESOLVER.guessFromPath("/static/image.png"))).isTrue();
        assertThat(MediaType.PDF.is(RESOLVER.guessFromPath("document.pdf"))).isTrue();
        assertThat(MediaType.WEBP.is(RESOLVER.guessFromPath("image.webp"))).isTrue();
        assertThat(MediaType.OCTET_STREAM.is(RESOLVER.guessFromPath("image.png.gz"))).isTrue();
    }

    @Test
    public void preCompressed() {
        assertThat(MediaType.PNG.is(RESOLVER.guessFromPath("image.png.gz", "gzip"))).isTrue();
        assertThat(MediaType.PNG.is(RESOLVER.guessFromPath("/static/image.png.br", "brotli"))).isTrue();
        assertThat(MediaType.OCTET_STREAM.is(RESOLVER.guessFromPath("image.png.gz", "identity"))).isTrue();
        assertThat(MediaType.OCTET_STREAM.is(RESOLVER.guessFromPath("image.png.gz", null))).isTrue();
    }

    @Test
    public void guessedByJdk() {
        assertThat(MediaType.ZIP.is(RESOLVER.guessFromPath("bundle.zip"))).isTrue();
    }

    @Test
    public void unknownExtension() {
        assertThat(RESOLVER.guessFromPath("unknown.extension")).isNull();
    }
}
