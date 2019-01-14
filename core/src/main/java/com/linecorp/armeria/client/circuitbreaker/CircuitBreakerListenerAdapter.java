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

package com.linecorp.armeria.client.circuitbreaker;

/**
 * A skeletal {@link CircuitBreakerListener} implementation in order for a user to implement only the methods
 * what he or she really needs.
 */
public class CircuitBreakerListenerAdapter implements CircuitBreakerListener {

    @Override
    public void onStateChanged(String circuitBreakerName, CircuitState state) throws Exception {}

    @Override
    public void onEventCountUpdated(String circuitBreakerName, EventCount eventCount) throws Exception {}

    @Override
    public void onRequestRejected(String circuitBreakerName) throws Exception {}
}
