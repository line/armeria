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

package com.linecorp.armeria.internal.server.hessian;

/**
 * Hessian header type.
 * @author eisig
 */
public enum HeaderType {

    CALL_1_REPLY_1, CALL_1_REPLY_2, HESSIAN_2, REPLY_1, REPLY_2;

    public boolean isCall2() {
        return this == HeaderType.HESSIAN_2;
    }

    public boolean isReply2() {
        switch (this) {
            case CALL_1_REPLY_2:
            case HESSIAN_2:
                return true;
            default:
                return false;
        }
    }
}
