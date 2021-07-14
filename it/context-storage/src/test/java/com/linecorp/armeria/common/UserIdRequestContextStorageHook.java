/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.common.RequestContextStorageHookTest.USER_ID_ATTR;

import javax.annotation.Nullable;

public enum UserIdRequestContextStorageHook implements RequestContextStorageHook {

    INSTANCE;

    private static final ThreadLocal<String> userId = new ThreadLocal<>();

    static String userId() {
        return userId.get();
    }

    @Override
    public RequestContextStorage apply(RequestContextStorage contextStorage) {
        return new RequestContextStorageWrapper(contextStorage) {

            @Nullable
            @Override
            public <T extends RequestContext> T push(RequestContext toPush) {
                userId.set(toPush.attr(USER_ID_ATTR));
                return super.push(toPush);
            }

            @Override
            public void pop(RequestContext current, @Nullable RequestContext toRestore) {
                userId.remove();
                super.pop(current, toRestore);
            }
        };
    }
}
