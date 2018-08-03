/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A callback which is invoked to handle SAML messages.
 */
@FunctionalInterface
interface SamlServiceFunction {
    /**
     * Invoked by the {@link SamlService} when a SAML message is received.
     *
     * @param ctx the {@link ServiceRequestContext} of {@code req}
     * @param msg the {@link AggregatedHttpMessage} being handled
     * @param defaultHostname the hostname which is specified by a user via the {@link SamlServiceProvider},
     *                        or the virtual hostname of the server if a user did not specify his or her
     *                        hostname
     * @param portConfig the port number and its {@link SessionProtocol} which the server is bound to
     */
    HttpResponse serve(ServiceRequestContext ctx, AggregatedHttpMessage msg,
                       String defaultHostname, SamlPortConfig portConfig);
}
