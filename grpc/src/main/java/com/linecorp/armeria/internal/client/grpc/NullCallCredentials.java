/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.client.grpc;

import java.util.concurrent.Executor;

import io.grpc.CallCredentials;

/**
 * A null {@link CallCredentials} for internal use.
 */
public final class NullCallCredentials extends CallCredentials {
    public static final CallCredentials INSTANCE = new NullCallCredentials();

    private NullCallCredentials() {}

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void thisUsesUnstableApi() {
        throw new UnsupportedOperationException();
    }
}
