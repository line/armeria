/*
 * Copyright 2016 LINE Corporation
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

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A function used for resolve {@link MediaType} of file.
 */
public interface MimeTypeFunction {

  /**
   * Resolve MIME types {@link MediaType} with given {@code path}.
   * @param path of file to resolved. For example "/foo/bar.txt" or "bar.txt"
   * @return the {@link MediaType}
   */
  MediaType guessFromPath(String path);

  /**
   * Resolve MIME types {@link MediaType} with given {@code path} and {@code contentEncoding}.
   * @param path of zipped file to resolved. For example "/foo/bar.txt.gz" or "bar.txt.br"
   * @param contentEncoding compress encoded type
   * @return the {@link MediaType}
   */
  MediaType guessFromPath(String path, @Nullable String contentEncoding);
}
