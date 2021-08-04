/*
 * Copyright 2019 LINE Corporation
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

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.unsafe.PooledObjects;

final class StreamMessageUtil {

    static void closeOrAbort(Object obj, @Nullable Throwable cause) {
        if (obj instanceof StreamMessage) {
            final StreamMessage<?> streamMessage = (StreamMessage<?>) obj;
            if (cause == null) {
                streamMessage.abort();
            } else {
                streamMessage.abort(cause);
            }
            return;
        }

        if (obj instanceof Publisher) {
            ((Publisher<?>) obj).subscribe(AbortingSubscriber.get(cause));
            return;
        }

        if (obj instanceof BodyPart) {
            final StreamMessage<HttpData> content = ((BodyPart) obj).content();
            if (cause == null) {
                content.abort();
            } else {
                content.abort(cause);
            }
            return;
        }

        if (obj instanceof SafeCloseable) {
            ((SafeCloseable) obj).close();
        }
    }

    static void closeOrAbort(Object obj) {
        closeOrAbort(obj, null);
    }

    static <T> T touchOrCopyAndClose(T obj, boolean withPooledObjects) {
        if (withPooledObjects) {
            return PooledObjects.touch(obj);
        } else {
            return PooledObjects.copyAndClose(obj);
        }
    }

    private StreamMessageUtil() {}
}
