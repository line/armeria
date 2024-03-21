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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBufAllocator;

/**
 * A builder for creating a {@link ByteStreamMessage} that reads data from the {@link Path} and publishes
 * using {@link HttpData}.
 */
@UnstableApi
public final class PathStreamMessageBuilder extends AbstractByteStreamMessageBuilder {

    private ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
    private final Path path;

    PathStreamMessageBuilder(Path path) {
        this.path = path;
    }

    /**
     * Sets the specified {@link ByteBufAllocator}.
     * If unspecified, {@link ByteBufAllocator#DEFAULT} is used by default.
     */
    public PathStreamMessageBuilder alloc(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        this.alloc = alloc;
        return this;
    }

    @Override
    public ByteStreamMessage build() {
        return new PathStreamMessage(path, executor(), alloc, bufferSize());
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public PathStreamMessageBuilder executor(ExecutorService executor) {
        return (PathStreamMessageBuilder) super.executor(executor);
    }

    @Override
    public PathStreamMessageBuilder bufferSize(int bufferSize) {
        return (PathStreamMessageBuilder) super.bufferSize(bufferSize);
    }
}
