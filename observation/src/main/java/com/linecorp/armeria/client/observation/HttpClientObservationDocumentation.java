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

    /**
     * TODO: Add docs.
     */
    OBSERVATION {
        @Override
        public Class<? extends ObservationConvention<? extends Context>> getDefaultConvention() {
            return DefaultHttpClientObservationConvention.class;
        }

        @Override
        public KeyName[] getLowCardinalityKeyNames() {
            return LowCardinalityKeys.values();
        }

        @Override
        public KeyName[] getHighCardinalityKeyNames() {
            return HighCardinalityKeys.values();
        }

        @Override
        public Event[] getEvents() {
            return Events.values();
        }
    };

    enum LowCardinalityKeys implements KeyName {

        /**
         * TODO: Add docs.
         */
        HTTP_METHOD {
            @Override
            public String asString() {
                return "http.method";
            }
        },

        /**
         * TODO: Add docs.
         */
        STATUS_CODE {
            @Override
            public String asString() {
                return "http.status_code";
            }
        },

        /**
         * TODO: Add docs.
         */
        HTTP_PROTOCOL {
            @Override
            public String asString() {
                return "http.protocol";
            }
        },

        /**
         * TODO: Add docs.
         */
        HTTP_SERIALIZATION_FORMAT {
            @Override
            public String asString() {
                return "http.serfmt";
            }
        }
    }

    enum HighCardinalityKeys implements KeyName {

        /**
         * TODO: Add docs.
         */
        HTTP_PATH {
            @Override
            public String asString() {
                return "http.path";
            }
        },

        /**
         * TODO: Add docs.
         */
        HTTP_HOST {
            @Override
            public String asString() {
                return "http.host";
            }
        },

        /**
         * TODO: Add docs.
         */
        HTTP_URL {
            @Override
            public String asString() {
                return "http.url";
            }
        },

        /**
         * TODO: Add docs.
         */
        ADDRESS_REMOTE {
            @Override
            public String asString() {
                return "address.remote";
            }
        },

        /**
         * TODO: Add docs.
         */
        ADDRESS_LOCAL {
            @Override
            public String asString() {
                return "address.local";
            }
        },

        /**
         * TODO: Add docs.
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

            @Override
            public String getContextualName() {
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

            @Override
            public String getContextualName() {
                return "wr";
            }
        }
    }
}
