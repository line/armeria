/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common;

import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Ignore;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageVerification;
import com.linecorp.armeria.internal.common.DefaultSplitHttpResponse;

import reactor.core.publisher.Flux;

public class DefaultSplitHttpResponseVerification extends StreamMessageVerification<HttpData> {

    protected DefaultSplitHttpResponseVerification() {
        super(new TestEnvironment(1000, 200));
    }

    @Override
    public StreamMessage<HttpData> createPublisher(long elements) {
        return newHttpResponse(elements).split().body();
    }

    private static HttpResponse newHttpResponse(long elements) {
        if (elements == 0) {
            return HttpResponse.of(HttpStatus.NO_CONTENT);
        }

        final HttpResponseWriter writer = HttpResponse.streaming();
        writer.write(ResponseHeaders.of(HttpStatus.OK));
        for (long i = 0; i < elements; i++) {
            writer.write(HttpData.ofUtf8(String.valueOf(elements)));
        }
        writer.whenConsumed().thenRun(writer::close);
        return writer;
    }

    @Override
    public StreamMessage<HttpData> createFailedPublisher() {
        return HttpResponse.of(Flux.error(ResponseTimeoutException.get())).split().body();
    }

    @Override
    public StreamMessage<HttpData> createAbortedPublisher(long elements) {
        final HttpResponse response = newHttpResponse(elements);
        if (elements == 0) {
            response.abort();
            return response.split().body();
        } else {
            final DefaultSplitHttpResponse bodyStream =
                    (DefaultSplitHttpResponse) response.split();
            final HttpResponseWriter writer = (HttpResponseWriter) response;
            writer.whenConsumed().thenRun(bodyStream::abort);
            return bodyStream;
        }
    }

    @Ignore
    @Override
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() throws Throwable {
        // Long.MAX_VALUE is too big to create fixed stream data
    }
}
