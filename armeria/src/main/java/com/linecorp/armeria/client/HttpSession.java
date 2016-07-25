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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.SessionProtocol;

interface HttpSession {

    HttpSession INACTIVE = new HttpSession() {
        @Override
        public SessionProtocol protocol() {
            return null;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean onRequestSent() {
            throw new IllegalStateException();
        }

        @Override
        public void retryWithH1C() {
            throw new IllegalStateException();
        }

        @Override
        public void deactivate() {}
    };

    SessionProtocol protocol();
    boolean isActive();
    boolean onRequestSent();
    void retryWithH1C();
    void deactivate();
}
