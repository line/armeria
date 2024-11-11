/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import com.linecorp.armeria.common.StreamTimeoutException;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Stream Timeout Mode consists of three modes.
 *
 * <ul>
 *   <li>{@link #UNTIL_FIRST} - Based on the first data chunk.
 *   If the first data chunk is not received within the specified time,
 *   a {@link StreamTimeoutException} is thrown.</li>
 *   <li>{@link #UNTIL_NEXT} - Based on each data chunk.
 *   If each data chunk is not received within the specified time after the previous chunk,
 *   a {@link StreamTimeoutException} is thrown.</li>
 *   <li>{@link #UNTIL_EOS} - Based on the entire stream.
 *   If all data chunks are not received within the specified time before the end of the stream,
 *   a {@link StreamTimeoutException} is thrown.</li>
 * </ul>
 */
@UnstableApi
public enum StreamTimeoutMode {

    /**
     * Based on the first data chunk.
     * If the first data chunk is not received within the specified time,
     * a {@link StreamTimeoutException} is thrown.
     */
    UNTIL_FIRST,

    /**
     * Based on each data chunk.
     * If each data chunk is not received within the specified time after the previous chunk,
     * a {@link StreamTimeoutException} is thrown.
     */
    UNTIL_NEXT,

    /**
     * Based on the entire stream.
     * If all data chunks are not received within the specified time before the end of the stream,
     * a {@link StreamTimeoutException} is thrown.
     */
    UNTIL_EOS
}
