/*
 * Copyright 2025 LINE Corporation
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

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Specifies the decoding mode for a {@code filename} parameter in
 * a {@link HttpHeaderNames#CONTENT_DISPOSITION} header.
 */
@UnstableApi
public enum MultipartDecodingMode {

    /**
     * Decodes the filename as a raw UTF-8 string. This is the default.
     */
    UTF_8,

    /**
     * Decodes the filename as a raw ISO-8859-1 string.
     */
    ISO_8859_1,

    /**
     * URL-decodes the filename using the UTF-8 charset.
     */
    URL_DECODING
}
