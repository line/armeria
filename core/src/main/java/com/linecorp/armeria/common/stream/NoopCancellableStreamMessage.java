/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.common.stream;

final class NoopCancellableStreamMessage extends CancellableStreamMessage<Object> {

    static final NoopCancellableStreamMessage INSTANCE = new NoopCancellableStreamMessage();

    @Override
    SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        throw new UnsupportedOperationException();
    }

    @Override
    void request(long n) {}

    @Override
    void cancel() {}

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public long demand() {
        return 0;
    }

    @Override
    public void abort() {}

    @Override
    public void abort(Throwable cause) {}

    private NoopCancellableStreamMessage() {}
}
