/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.observation;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.Observation.Event;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * TODO: Add me.
 */
public enum ServiceObservationDocumentation implements ObservationDocumentation {

    // TODO: Add me.
    OBSERVATION {
        @Override
        public Class<? extends ObservationConvention<? extends Context>> getDefaultConvention() {
            return DefaultServiceObservationConvention.class;
        }

        // TODO: Figure what should be low and what high cardinality

        @Override
        public KeyName[] getHighCardinalityKeyNames() {
            return HighCardinalityKeys.values();
        }

        @Override
        public Event[] getEvents() {
            return Events.values();
        }
    };

    enum HighCardinalityKeys implements KeyName {

        /**
         * TODO: Add me.
         */
        HTTP_PATH {
            @Override
            public String asString() {
                return "http.path";
            }
        },

        /**
         * TODO: Add me.
         */
        HTTP_METHOD {
            @Override
            public String asString() {
                return "http.method";
            }
        },

        /**
         * TODO: Add me.
         */
        HTTP_HOST {
            @Override
            public String asString() {
                return "http.host";
            }
        },

        /**
         * TODO: Add me.
         */
        HTTP_URL {
            @Override
            public String asString() {
                return "http.url";
            }
        },

        /**
         * TODO: Add me.
         */
        HTTP_PROTOCOL {
            @Override
            public String asString() {
                return "http.protocol";
            }
        },

        /**
         * TODO: Add me.
         */
        HTTP_SERIALIZATION_FORMAT {
            @Override
            public String asString() {
                return "http.serfmt";
            }
        },

        /**
         * TODO: Add me.
         */
        ADDRESS_REMOTE {
            @Override
            public String asString() {
                return "address.remote";
            }
        },

        /**
         * TODO: Add me.
         */
        ADDRESS_LOCAL {
            @Override
            public String asString() {
                return "address.local";
            }
        },

        /**
         * TODO: Add me.
         */
        STATUS_CODE {
            @Override
            public String asString() {
                return "http.status_code";
            }
        },

        /**
         * TODO: Add me.
         */
        ERROR {
            @Override
            public String asString() {
                return "error";
            }
        }
    }

    enum Events implements Event {

        /**
         * Semi-official annotation for the time the first bytes were sent on the wire.
         */
        WIRE_SEND {
            @Override
            public String getName() {
                return "ws";
            }
        },

        /**
         * Semi-official annotation for the time the first bytes were received on the wire.
         */
        WIRE_RECEIVE {
            @Override
            public String getName() {
                return "wr";
            }
        }
    }
}
