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

import java.net.URI;

import com.linecorp.armeria.common.Request;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.Observation.Event;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

enum HttpClientObservationDocumentation implements ObservationDocumentation {

    /**
     * A span collected by {@link ObservationClient}.
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
         * The HTTP method of the request.
         */
        HTTP_METHOD {
            @Override
            public String asString() {
                return "http.method";
            }
        },

        /**
         * The session-level protocol used for the request.
         */
        STATUS_CODE {
            @Override
            public String asString() {
                return "http.status_code";
            }
        },

        /**
         * The session-level protocol used for the request.
         */
        HTTP_PROTOCOL {
            @Override
            public String asString() {
                return "http.protocol";
            }
        },

        /**
         * The serialization format used for the HTTP request if exists.
         * An example can be the `gproto` format when using gRPC.
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
         * The absolute path part of the current {@link Request} URI, excluding the query part.
         */
        HTTP_PATH {
            @Override
            public String asString() {
                return "http.path";
            }
        },

        /**
         * The authority of the current {@link Request}.
         */
        HTTP_HOST {
            @Override
            public String asString() {
                return "http.host";
            }
        },

        /**
         * The {@link URI} associated with the current {@link Request}.
         */
        HTTP_URL {
            @Override
            public String asString() {
                return "http.url";
            }
        },

        /**
         * The remote address of this request.
         */
        ADDRESS_REMOTE {
            @Override
            public String asString() {
                return "address.remote";
            }
        },

        /**
         * The local address of this request.
         */
        ADDRESS_LOCAL {
            @Override
            public String asString() {
                return "address.local";
            }
        },

        /**
         * The response cause for why the request has failed.
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
