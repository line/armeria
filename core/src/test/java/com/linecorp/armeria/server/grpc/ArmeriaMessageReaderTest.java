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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.server.grpc.ArmeriaGrpcServerStream.TransportState;

import io.grpc.internal.ReadableBuffer;

// Client error is tested in ArmeriaGrpcServerStreamTest, we can't check it here since
// TransportState.transportReportStatus is marked final.
public class ArmeriaMessageReaderTest {

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private TransportState transportState;

    @Mock
    private Subscription subscription;

    private ArmeriaMessageReader reader;

    @Before
    public void setUp() {
        reader = new ArmeriaMessageReader(transportState);
        reader.onSubscribe(subscription);
        verify(subscription).request(1);
        reset(subscription);
    }

    @Test
    public void sendMessage() {
        byte[] msg = new byte[] { 1, 2, 3, 5};
        reader.onNext(HttpData.of(msg));
        // ReadableBuffer doesn't seem to implement equals so we need to capture.
        ArgumentCaptor<ReadableBuffer> bufferCaptor = ArgumentCaptor.forClass(ReadableBuffer.class);
        verify(transportState).inboundDataReceived(bufferCaptor.capture(), eq(false));
        verify(subscription).request(1);
        assertThat(bufferCaptor.getValue().array()).isEqualTo(msg);
    }

    @Test
    public void clientDone() {
        reader.onComplete();
        verify(transportState).endOfStream();
    }

    @Test
    public void serverCancelled() {
        reader.cancel();
        verify(subscription).cancel();
    }
}
