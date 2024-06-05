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

package com.linecorp.armeria.common.stream;

import java.io.InputStream;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating a {@link ByteStreamMessage} that reads data from the {@link InputStream}
 * and publishes using {@link HttpData}.
 */
@UnstableApi
public final class InputStreamStreamMessageBuilder
        extends AbstractByteStreamMessageBuilder<InputStreamStreamMessageBuilder> {

    private final InputStream inputStream;

    InputStreamStreamMessageBuilder(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public ByteStreamMessage build() {
        return new InputStreamStreamMessage(inputStream, executor(), bufferSize());
    }
}
