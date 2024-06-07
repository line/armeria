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

package com.linecorp.armeria.common.stream;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ExecutorService;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil;

abstract class AbstractByteStreamMessageBuilder<SELF extends AbstractByteStreamMessageBuilder<SELF>> {

    private int bufferSize = InternalStreamMessageUtil.DEFAULT_FILE_BUFFER_SIZE;

    @Nullable
    private ExecutorService executor;

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    @Nullable
    final ExecutorService executor() {
        return executor;
    }

    /**
     * Sets the specified {@link ExecutorService} that performs blocking IO read operations.
     */
    public SELF executor(ExecutorService executor) {
        requireNonNull(executor, "executor");
        this.executor = executor;
        return self();
    }

    final int bufferSize() {
        return bufferSize;
    }

    /**
     * Sets the buffer size used to create a buffer used to read data from the source.
     * The newly created {@link StreamMessage} will emit {@link HttpData}s chunked to
     * size less than or equal to the buffer size.
     * If unspecified, {@value InternalStreamMessageUtil#DEFAULT_FILE_BUFFER_SIZE} is used by default.
     *
     * @throws IllegalArgumentException if the {@code bufferSize} is non-positive.
     */
    public SELF bufferSize(int bufferSize) {
        checkArgument(bufferSize > 0, "bufferSize: %s (expected: > 0)", bufferSize);
        this.bufferSize = bufferSize;
        return self();
    }

    /**
     * Returns a newly created {@link ByteStreamMessage} with the properties set so far.
     */
    public abstract ByteStreamMessage build();
}
