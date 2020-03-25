/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.common;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

/**
 * An idle state event triggered by {@link IdleTimeoutScheduler}.
 */
class IdleStateEvent {

    // Forked from Netty 4.1.48
    // https://github.com/netty/netty/blob/81513c3728df8add3c94fd0bdaaf9ba424925b29/handler/src/main/java/io/netty/handler/timeout/IdleStateEvent.java

    public static final IdleStateEvent FIRST_ALL_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.ALL_IDLE, true);
    public static final IdleStateEvent ALL_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.ALL_IDLE, false);
    public static final IdleStateEvent FIRST_PING_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.PING_IDLE, true);
    public static final IdleStateEvent PING_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.PING_IDLE, false);

    private final IdleState state;
    private final boolean first;

    /**
     * Constructor for sub-classes.
     *
     * @param state the {@link IdleStateEvent} which triggered the event.
     * @param first {@code true} if its the first idle event for the {@link IdleStateEvent}.
     */
    IdleStateEvent(IdleState state, boolean first) {
        this.state = ObjectUtil.checkNotNull(state, "state");
        this.first = first;
    }

    /**
     * Returns the idle state.
     */
    IdleState state() {
        return state;
    }

    /**
     * Returns {@code true} if this was the first event for the {@link io.netty.handler.timeout.IdleState}.
     */
    boolean isFirst() {
        return first;
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(this) + '(' + state + (first ? ", first" : "") + ')';
    }

    private static final class DefaultIdleStateEvent extends IdleStateEvent {
        private final String representation;

        DefaultIdleStateEvent(IdleState state, boolean first) {
            super(state, first);
            representation = "IdleStateEvent(" + state + (first ? ", first" : "") + ')';
        }

        @Override
        public String toString() {
            return representation;
        }
    }
}
