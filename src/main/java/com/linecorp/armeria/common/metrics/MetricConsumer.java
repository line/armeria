/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.common.metrics;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;

public interface MetricConsumer {

    /**
     * Invoked when a request has been streamed.
     */
    void onRequest(RequestLog req);

    /**
     * Invoked when a response has been streamed.
     */
    void onResponse(ResponseLog res);

    default MetricConsumer andThen(MetricConsumer other) {
        Objects.requireNonNull(other, "other");
        MetricConsumer outer = this;
        return new MetricConsumer() {
            @Override
            public void onRequest(RequestLog req) {
                try {
                    outer.onRequest(req);
                } catch (Throwable e) {
                    LoggerFactory.getLogger(MetricConsumer.class)
                                 .warn("invocationStarted() failed with an exception: {}", e);
                }
                other.onRequest(req);
            }

            @Override
            public void onResponse(ResponseLog res) {
                try {
                    outer.onResponse(res);
                } catch (Throwable e) {
                    LoggerFactory.getLogger(MetricConsumer.class)
                                 .warn("invocationComplete() failed with an exception: {}", e);
                }
                other.onResponse(res);
            }
        };
    }
}

