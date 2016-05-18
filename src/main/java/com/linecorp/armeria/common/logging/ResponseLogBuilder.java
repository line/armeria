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

public interface ResponseLogBuilder extends MessageLogBuilder {

    ResponseLogBuilder NOOP = new ResponseLogBuilder() {
        @Override
        public boolean isStarted() {
            return true;
        }

        @Override
        public void statusCode(int statusCode) {}

        @Override
        public void start() {}

        @Override
        public void increaseContentLength(long deltaBytes) {}

        @Override
        public void contentLength(long contentLength) {}

        @Override
        public void attach(Object attachment) {}

        @Override
        public void end() {}

        @Override
        public void end(Throwable cause) {}
    };

    void start();
    void statusCode(int statusCode);
}
