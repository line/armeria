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
package com.linecorp.armeria.server.jetty;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

final class ArmeriaEndPoint extends AbstractArmeriaEndPoint {

    ArmeriaEndPoint(ServiceRequestContext ctx, @Nullable String hostname) {
        super(ctx, hostname);
    }

    @Override
    public void close(Throwable cause) {
        close0(cause);
    }

    @Override
    public void onClose(Throwable cause) {
        onClose0(cause);
    }
}
