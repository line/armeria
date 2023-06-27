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

package com.linecorp.armeria.client.observation;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.Observation.Event;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

enum HttpClientObservationDocumentation implements ObservationDocumentation {

    OBSERVATION {
        @Override
        public Class<? extends ObservationConvention<? extends Context>> getDefaultConvention() {
            return DefaultHttpClientObservationConvention.class;
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

        HTTP_PATH {
            @Override
            public String asString() {
                return "http.path";
            }
        },

        HTTP_METHOD {
            @Override
            public String asString() {
                return "http.method";
            }
        },

        HTTP_HOST {
            @Override
            public String asString() {
                return "http.host";
            }
        },

        HTTP_URL {
            @Override
            public String asString() {
                return "http.url";
            }
        },

        HTTP_PROTOCOL {
            @Override
            public String asString() {
                return "http.protocol";
            }
        },

        HTTP_SERIALIZATION_FORMAT {
            @Override
            public String asString() {
                return "http.serfmt";
            }
        },

        ADDRESS_REMOTE {
            @Override
            public String asString() {
                return "address.remote";
            }
        },

        ADDRESS_LOCAL {
            @Override
            public String asString() {
                return "address.local";
            }
        },

        STATUS_CODE {
            @Override
            public String asString() {
                return "http.status_code";
            }
        },

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
