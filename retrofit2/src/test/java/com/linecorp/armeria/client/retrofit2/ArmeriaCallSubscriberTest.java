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
package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class ArmeriaCallSubscriberTest {

    private static class ManualMockCallback implements Callback {
        private int callbackCallingCount;
        private Response response;
        private IOException exception;

        @Override
        public void onFailure(Call call, IOException e) {
            callbackCallingCount++;
            exception = e;
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            callbackCallingCount++;
            this.response = response;
        }
    }

    @Mock
    ArmeriaCall armeriaCall;

    @Mock
    Subscription subscription;

    @Rule
    public MockitoRule mockingRule = MockitoJUnit.rule();

    @Test
    public void completeNormally() throws Exception {

        when(armeriaCall.tryFinish()).thenReturn(true);

        ManualMockCallback callback = new ManualMockCallback();
        ArmeriaCallSubscriber subscriber = new ArmeriaCallSubscriber(armeriaCall,
                                                                     callback,
                                                                     new Request.Builder().url("http://foo.com")
                                                                                          .build());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(HttpHeaders.of(200));
        subscriber.onNext(HttpData.ofUtf8("{\"name\":\"foo\"}"));
        subscriber.onComplete();

        verify(subscription).request(Long.MAX_VALUE);
        assertThat(callback.callbackCallingCount).isEqualTo(1);
        assertThat(callback.response.body().string()).isEqualTo("{\"name\":\"foo\"}");
    }

    @Test
    public void cancel() throws Exception {

        when(armeriaCall.tryFinish()).thenReturn(false);
        when(armeriaCall.isCanceled()).thenReturn(false, false, true);

        ManualMockCallback callback = new ManualMockCallback();
        ArmeriaCallSubscriber subscriber = new ArmeriaCallSubscriber(armeriaCall,
                                                                     callback,
                                                                     new Request.Builder().url("http://foo.com")
                                                                                          .build());
        subscriber.onSubscribe(subscription);
        subscriber.onNext(HttpHeaders.of(200));
        subscriber.onNext(HttpData.ofUtf8("{\"name\":\"foo\"}"));
        subscriber.onComplete();

        verify(subscription).request(Long.MAX_VALUE);
        assertThat(callback.callbackCallingCount).isEqualTo(1);
        assertThat(callback.exception.getMessage()).isEqualTo("Canceled");
    }
}
