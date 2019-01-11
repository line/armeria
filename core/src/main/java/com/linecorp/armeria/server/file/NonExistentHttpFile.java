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
package com.linecorp.armeria.server.file;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;

import io.netty.buffer.ByteBufAllocator;

final class NonExistentHttpFile implements AggregatedHttpFile {

    static final NonExistentHttpFile INSTANCE = new NonExistentHttpFile();

    private NonExistentHttpFile() {}

    @Override
    public HttpFileAttributes readAttributes() {
        return null;
    }

    @Nullable
    @Override
    public HttpHeaders readHeaders() {
        return null;
    }

    @Override
    public HttpResponse read(Executor fileReadExecutor, ByteBufAllocator alloc) {
        return null;
    }

    @Nullable
    @Override
    public HttpData content() {
        return null;
    }

    @Override
    public HttpService asService() {
        return (ctx, req) -> {
            switch (req.method()) {
                case HEAD:
                case GET:
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                default:
                    return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            }
        };
    }
}
