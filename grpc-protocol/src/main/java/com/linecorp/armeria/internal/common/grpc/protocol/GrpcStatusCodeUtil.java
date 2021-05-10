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
 * under the License
 */

package com.linecorp.armeria.internal.common.grpc.protocol;

public final class GrpcStatusCodeUtil {

    /**
     * gRPC uses the status codes from 0 to 16.
     * <a href="https://grpc.github.io/grpc/core/md_doc_statuscodes.html">Status codes and their use in gRPC</a>
     */
    private static final String[] codeStrings = new String[17];

    static {
        for (int code = 0; code < codeStrings.length; code++) {
            codeStrings[code] = String.valueOf(code);
        }
    }

    public static String intToString(int code) {
        return codeStrings[code];
    }

    private GrpcStatusCodeUtil() {}
}
