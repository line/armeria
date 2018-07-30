/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import java.io.IOException;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.HttpData;

import okhttp3.Callback;
import okhttp3.Request;
import okio.Buffer;

final class BlockingCallSubscriber extends AbstractSubscriber {

    private final Buffer responseDataBuffer = new Buffer();

    BlockingCallSubscriber(ArmeriaCall armeriaCall, Callback callback, Request request) {
        super(armeriaCall, request, callback, MoreExecutors.directExecutor());
    }

    @Override
    public void onError0(IOException e) {
        safeOnFailure(e);
    }

    @Override
    public void onComplete0() {
        safeOnResponse(responseDataBuffer);
    }

    @Override
    void onSubscribe0() {
        request(Long.MAX_VALUE);
    }

    @Override
    void onHttpData(HttpData data) {
        responseDataBuffer.write(data.array(), data.offset(), data.length());
    }

    @Override
    void onHttpHeaders() {
    }

    @Override
    void onCancelled() {
        safeOnFailure(newCancelledException());
    }
}
