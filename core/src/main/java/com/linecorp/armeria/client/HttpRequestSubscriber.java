/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.channel.Channel;

class HttpRequestSubscriber extends AbstractHttpRequestSubscriber {

    HttpRequestSubscriber(Channel ch, ClientHttpObjectEncoder encoder, HttpResponseDecoder responseDecoder,
                          HttpRequest request, DecodedHttpResponse originalRes,
                          ClientRequestContext ctx, long timeoutMillis) {
        super(ch, encoder, responseDecoder, request, originalRes, ctx, timeoutMillis, true, true, false);
    }

    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            failAndReset(new IllegalArgumentException(
                    "published an HttpObject that's neither Http2Headers nor Http2Data: " + o));
            PooledObjects.close(o);
            return;
        }

        switch (state()) {
            case NEEDS_DATA_OR_TRAILERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailers = (HttpHeaders) o;
                    if (trailers.contains(HttpHeaderNames.STATUS)) {
                        failAndReset(new IllegalArgumentException("published a trailers with status: " + o));
                        return;
                    }
                    // Trailers always end the stream even if not explicitly set.
                    writeTrailers(trailers);
                } else {
                    writeData((HttpData) o);
                }
                channel().flush();
                break;
            }
            case DONE:
                // Cancel the subscription if any message comes here after the state has been changed to DONE.
                cancel();
                PooledObjects.close(o);
                break;
        }
    }
}
