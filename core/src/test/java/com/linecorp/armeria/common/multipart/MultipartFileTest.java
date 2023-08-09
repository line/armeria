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
package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

/**
 * Tests {@link MultipartFile}.
 */
class MultipartFileTest {

    @TempDir
    static Path tempDir;

    @Test
    void defaultContentType() {
        final MultipartFile multipartFile = MultipartFile.of("file", "hello", tempDir);
        final HttpHeaders headers = multipartFile.headers();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.OCTET_STREAM);
    }

    @Test
    void defaultContentTypeOfTextFile() {
        final MultipartFile multipartFile = MultipartFile.of("file", "hello.txt", tempDir);
        final HttpHeaders headers = multipartFile.headers();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.PLAIN_TEXT);
    }

    @Test
    void defaultContentTypeOfImageFile() {
        final MultipartFile multipartFile = MultipartFile.of("file", "hello.jpg", tempDir);
        final HttpHeaders headers = multipartFile.headers();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.JPEG);
    }
}
