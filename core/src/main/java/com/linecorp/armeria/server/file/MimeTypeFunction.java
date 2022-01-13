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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A function used for determining the {@link MediaType} of a file based on its path.
 */
@UnstableApi
public interface MimeTypeFunction {

    /**
     * Returns the default {@link MimeTypeFunction}.
     */
    static MimeTypeFunction ofDefault() {
        return MimeTypeUtil.getDefaultMimeTypeFunction();
    }

    /**
     * Resolves the {@link MediaType} of the file referred by the given {@code path}.
     * @param path the path to the file to resolve its {@link MediaType}, e.g. {@code "/foo/bar.txt"}
     * or {@code "bar.txt"}.
     * @return the resolved {@link MediaType}
     */
    @Nullable MediaType guessFromPath(String path);

    /**
     * Resolves the {@link MediaType} of the file referred by the given {@code path} assuming
     * the file is encoded in the given {@code contentEncoding}.
     * @param path the path of the file to resolve its {@link MediaType}, usually in a compressed form,
     * e.g. {@code "/foo/bar.txt.gz"} or {@code "bar.txt.br"}.
     * @param contentEncoding the content encoding, such as {@code "gzip"} and {@code "br"}, as defined in
     * <a href="https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.5">the section 3.5, RFC 2616</a>.
     * @return the resolved {@link MediaType}
     */
    @Nullable MediaType guessFromPath(String path, @Nullable String contentEncoding);

    /**
     * Returns a newly created {@link MimeTypeFunction} that tries this {@link MimeTypeFunction} first and
     * then the specified {@code other} when the first call returns {@code null}.
     */
    default MimeTypeFunction orElse(MimeTypeFunction other) {
        requireNonNull(other, "other");
        if (this == other) {
            return this;
        }
        return new MimeTypeFunction() {
            @Override
            public @Nullable MediaType guessFromPath(String path) {
                final @Nullable MediaType mediaType = MimeTypeFunction.this.guessFromPath(path);
                if (mediaType != null) {
                    return mediaType;
                }
                return other.guessFromPath(path);
            }

            @Override
            public @Nullable MediaType guessFromPath(String path, @Nullable String contentEncoding) {
                final @Nullable MediaType mediaType = MimeTypeFunction.this.guessFromPath(path,
                                                                                          contentEncoding);
                if (mediaType != null) {
                    return mediaType;
                }
                return other.guessFromPath(path, contentEncoding);
            }
        };
    }
}
