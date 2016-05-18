/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.http;

import java.util.Arrays;

import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.internal.http.ArmeriaHttpUtil;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

@SuppressWarnings("deprecation")
final class DefaultSimpleHttpClient implements SimpleHttpClient {

    private final DefaultHttpClient client;

    DefaultSimpleHttpClient(DefaultHttpClient client) {
        this.client = client;
    }

    @Override
    public Future<SimpleHttpResponse> execute(SimpleHttpRequest sReq) {
        final EventLoop eventLoop = client.eventLoop0();
        final Promise<SimpleHttpResponse> promise = eventLoop.newPromise();
        try {
            final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                    HttpMethod.valueOf(sReq.method().name()),
                    sReq.uri().getPath(),
                    HttpData.of(sReq.content()));

            // Convert the headers.
            ArmeriaHttpUtil.toArmeria(sReq.headers(), aReq.headers());

            final HttpResponse res = client.execute(eventLoop, aReq);
            res.aggregate().whenComplete((aRes, cause) -> {
                if (cause != null) {
                    promise.setFailure(cause);
                } else {
                    try {
                        final HttpData aContent = aRes.content();
                        final byte[] content;
                        if (aContent.offset() == 0 && aContent.length() == aContent.array().length) {
                            content = aContent.array();
                        } else {
                            content = Arrays.copyOfRange(aContent.array(), aContent.offset(),
                                                         aContent.length());
                        }

                        final SimpleHttpResponse sRes = new SimpleHttpResponse(
                                HttpResponseStatus.valueOf(aRes.status().code()),
                                ArmeriaHttpUtil.toNettyHttp1(aRes.headers()),
                                content);

                        promise.setSuccess(sRes);
                    } catch (Throwable t) {
                        promise.setFailure(t);
                    }
                }
            });
        } catch (Throwable t) {
            promise.setFailure(t);
        }

        return promise;
    }

    @Override
    public SimpleHttpClient withOptions(Iterable<ClientOptionValue<?>> additionalOptions) {
        return new DefaultSimpleHttpClient((DefaultHttpClient) client.withOptions(additionalOptions));
    }
}
