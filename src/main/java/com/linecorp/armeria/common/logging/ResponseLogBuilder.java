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

package com.linecorp.armeria.common.logging;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Updates a {@link ResponseLog} with newly available information.
 */
public interface ResponseLogBuilder extends MessageLogBuilder {

    /**
     * A dummy {@link ResponseLogBuilder} that discards everything it collected.
     */
    ResponseLogBuilder NOOP = new ResponseLogBuilder() {
        @Override
        public void statusCode(int statusCode) {}

        @Override
        public void start() {}

        @Override
        public void increaseContentLength(long deltaBytes) {}

        @Override
        public void contentLength(long contentLength) {}

        @Override
        public <T> Attribute<T> attr(AttributeKey<T> key) {
            return new NoopAttribute<>(key);
        }

        @Override
        public <T> boolean hasAttr(AttributeKey<T> key) {
            return false;
        }

        @Override
        public void end() {}

        @Override
        public void end(Throwable cause) {}
    };

    /**
     * Starts the collection of information. This method will update the {@link MessageLog#startTimeNanos()}
     * property. This method will do nothing if called twice.
     */
    void start();

    /**
     * Updates the status code specific to the current {@link SessionProtocol}.
     */
    void statusCode(int statusCode);
}
