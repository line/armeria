/*
 * Copyright 2019 LINE Corporation
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
 * Copyright 2015 The gRPC Authors
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

package com.linecorp.armeria.common.grpc.protocol;

import java.io.IOException;
import java.io.InputStream;

import com.linecorp.armeria.common.util.UnstableApi;

/**
 * Represents a message decompressor.
 */
@UnstableApi
public interface Decompressor {

    // Copied from `io.grpc.Decompressor` at:
    // https://github.com/grpc/grpc-java/blob/80c3c992a66aa21ccf3e12e38000316e45f97e64/api/src/main/java/io/grpc/Decompressor.java

    /**
     * Returns the message encoding that this compressor uses.
     *
     * <p>This can be values such as "gzip", "deflate", "snappy", etc.
     */
    String getMessageEncoding();

    /**
     * Wraps an existing input stream with a decompressing input stream.
     * @param is The input stream of uncompressed data
     * @return An input stream that decompresses
     */
    InputStream decompress(InputStream is) throws IOException;
}
