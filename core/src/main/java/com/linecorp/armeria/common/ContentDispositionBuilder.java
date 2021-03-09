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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

/**
 * A builder class for creating {@link ContentDisposition}.
 */
public final class ContentDispositionBuilder {

    // Forked from https://github.com/spring-projects/spring-framework/blob/d9ccd618ea9cbf339eb5639d24d5a5fabe8157b5/spring-web/src/main/java/org/springframework/http/ContentDisposition.java

    private final String type;

    @Nullable
    private String name;

    @Nullable
    private String filename;

    @Nullable
    private Charset charset;

    ContentDispositionBuilder(String type) {
        this.type = type;
    }

    /**
     * Sets the value of the {@code name} parameter.
     */
    public ContentDispositionBuilder name(String name) {
        requireNonNull(name, "name");
        this.name = name;
        return this;
    }

    /**
     * Sets the value of the {@code filename} parameter. The given
     * filename will be formatted as quoted-string, as defined in RFC 2616,
     * section 2.2, and any quote characters within the filename value will
     * be escaped with a backslash, e.g. {@code "foo\"bar.txt"} becomes
     * {@code "foo\\\"bar.txt"}.
     */
    public ContentDispositionBuilder filename(String filename) {
        return filename(filename, null);
    }

    /**
     * Sets the value of the {@code filename*} that will be encoded as defined in the RFC 5987.
     * Only the US-ASCII, UTF-8 and ISO-8859-1 charsets are supported.
     *
     * <p><strong>Note:</strong> Do not use this for a {@code "multipart/form-data"} requests as per
     * <a link="https://datatracker.ietf.org/doc/html/rfc7578#section-4.2">RFC 7578, Section 4.2</a>
     * and also RFC 5987 itself mentions it does not apply to multipart requests.
     */
    public ContentDispositionBuilder filename(String filename, @Nullable Charset charset) {
        requireNonNull(filename, "filename");
        checkArgument(!filename.isEmpty(), "filename should not be empty.");
        this.filename = filename;
        if (charset != null) {
            checkArgument(charset == StandardCharsets.US_ASCII || charset == StandardCharsets.UTF_8 ||
                          charset == StandardCharsets.ISO_8859_1,
                          "Charset: %s (expected: US-ASCII, UTF-8 or ISO-8859-1)", charset);
        }
        this.charset = charset;
        return this;
    }

    /**
     * Returns a newly-created {@link ContentDisposition} based on the properties of this builder.
     */
    public ContentDisposition build() {
        return new ContentDisposition(type, name, filename, charset);
    }
}
