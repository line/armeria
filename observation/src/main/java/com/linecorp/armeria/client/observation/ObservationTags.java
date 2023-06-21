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

package com.linecorp.armeria.client.observation;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;

import io.micrometer.observation.transport.RequestReplySenderContext;

final class ObservationTags {

    public static boolean updateRemoteEndpoint(RequestReplySenderContext<?, ?> senderContext,
                                               RequestContext ctx) {
        final InetSocketAddress remoteAddress = ctx.remoteAddress();
        if (remoteAddress != null) {
            try {
                senderContext.setRemoteServiceAddress(
                        remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort());
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
        return false;
    }

    private ObservationTags() {}
}
