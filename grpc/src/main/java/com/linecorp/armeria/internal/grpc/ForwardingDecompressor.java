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

package com.linecorp.armeria.internal.grpc;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.grpc.protocol.Decompressor;

import io.grpc.Codec.Identity;

/**
 * A {@link Decompressor} that forwards to a {@link io.grpc.Decompressor}.
 */
public final class ForwardingDecompressor implements Decompressor {

    @Nullable
    public static ForwardingDecompressor forGrpc(io.grpc.Decompressor delegate) {
        requireNonNull(delegate, "delegate");
        if (delegate == Identity.NONE) {
            return null;
        }
        return new ForwardingDecompressor(delegate);
    }

    private final io.grpc.Decompressor delegate;

    private ForwardingDecompressor(io.grpc.Decompressor delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getMessageEncoding() {
        return delegate.getMessageEncoding();
    }

    @Override
    public InputStream decompress(InputStream is) throws IOException {
        return delegate.decompress(is);
    }
}
