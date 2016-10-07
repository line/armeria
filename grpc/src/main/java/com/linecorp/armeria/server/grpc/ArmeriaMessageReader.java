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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpObject;
import com.linecorp.armeria.server.grpc.ArmeriaGrpcServerStream.TransportState;

import io.grpc.Status;
import io.grpc.internal.ReadableBuffers;

/**
 * A {@link Subscriber} to read request data and pass it to a GRPC {@link TransportState}
 * for processing. GRPC code will then handle deframing, decompressing, etc.
 */
class ArmeriaMessageReader implements Subscriber<HttpObject> {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaMessageReader.class);

    private final ArmeriaGrpcServerStream.TransportState transportState;

    private Subscription subscription;

    ArmeriaMessageReader(TransportState transportState) {
        this.transportState = transportState;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(HttpObject obj) {
        if (obj instanceof HttpHeaders) {
            // GRPC clients never send trailing headers so we should treat this as a bad request.
            logger.info("Trailing headers received from GRPC client, this should never happen: {}.", obj);
            transportState.transportReportStatus(Status.ABORTED);
            subscription.cancel();
            return;
        }
        HttpData data = (HttpData) obj;
        transportState.inboundDataReceived(ReadableBuffers.wrap(data.array(), data.offset(), data.length()),
                                           false);
        subscription.request(1);
    }

    @Override
    public void onError(Throwable cause) {
        transportState.transportReportStatus(Status.fromThrowable(cause));
    }

    @Override
    public void onComplete() {
        transportState.endOfStream();
    }

    void cancel() {
        subscription.cancel();
    }
}
