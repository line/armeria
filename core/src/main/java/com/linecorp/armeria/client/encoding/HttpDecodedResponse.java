/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.encoding;

import java.util.Map;

import org.reactivestreams.Subscriber;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatusClass;

/**
 * A {@link FilteredHttpResponse} that applies HTTP decoding to {@link HttpObject}s as they are published.
 */
class HttpDecodedResponse extends FilteredHttpResponse {

    private final Map<String, StreamDecoderFactory> availableDecoders;

    private StreamDecoder responseDecoder;
    private boolean headersReceived;

    HttpDecodedResponse(HttpResponse delegate, Map<String, StreamDecoderFactory> availableDecoders) {
        super(delegate);
        this.availableDecoders = availableDecoders;
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof HttpHeaders) {
            HttpHeaders headers = (HttpHeaders) obj;

            // Skip informational headers.
            if (headers.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                return obj;
            }

            if (headersReceived) {
                // Trailing headers, no modification.
                return obj;
            }
            headersReceived = true;

            String contentEncoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
            if (contentEncoding != null) {
                StreamDecoderFactory decoderFactory = availableDecoders.get(Ascii.toLowerCase(contentEncoding));
                // If the server returned an encoding we don't support (shouldn't happen since we set
                // Accept-Encoding), decoding will be skipped which is ok.
                if (decoderFactory != null) {
                    responseDecoder = decoderFactory.newDecoder();
                }
            }

            return headers;
        }

        assert obj instanceof HttpData;

        return responseDecoder != null ? responseDecoder.decode((HttpData) obj) : obj;
    }

    @Override
    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
        if (responseDecoder == null) {
            return;
        }
        HttpData lastData = responseDecoder.finish();
        if (!lastData.isEmpty()) {
            subscriber.onNext(lastData);
        }
    }

    @Override
    protected void beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        if (responseDecoder == null) {
            return;
        }
        responseDecoder.finish();
    }
}
