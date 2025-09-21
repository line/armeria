/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.fail;

import java.io.OutputStream;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.encoding.StreamEncoderFactories;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;

import io.netty.buffer.ByteBufOutputStream;

class HttpEncodersTest {
    private static final Map<String, StreamEncoderFactory> allHeaderToEncoderFactory;

    static {
        allHeaderToEncoderFactory = StreamEncoderFactory.all()
                                                        .stream()
                                                        .collect(
                                                                ImmutableMap.toImmutableMap(
                                                                        StreamEncoderFactory
                                                                                ::encodingHeaderValue,
                                                                        f -> f)
                                                        );
    }

    @Test
    void noAcceptEncoding() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        assertThat(HttpEncoders.determineEncoder(allHeaderToEncoderFactory, headers)).isNull();
        assertThat(HttpEncoders.determineEncoder(ImmutableMap.of(), headers)).isNull();
        assertThat(HttpEncoders.determineEncoder(
                           ImmutableMap.of("gzip", StreamEncoderFactories.GZIP), headers
                   )
        ).isNull();
    }

    @Test
    void acceptEncodingGzip() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                "x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isEqualTo(StreamEncoderFactories.GZIP);

        // GZIP is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY,
                                "deflate", StreamEncoderFactories.DEFLATE), headers
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
    }

    @Test
    void acceptEncodingDeflate() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "deflate");
        assertThat(HttpEncoders.determineEncoder(ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE),
                                                 headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE,
                                "x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // DEFLATE is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY,
                                "gzip", StreamEncoderFactories.GZIP), headers
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
    }

    @Test
    void acceptEncodingBrotli() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "br");
        @Nullable
        final StreamEncoderFactory expectedBrotliFactory =
                allHeaderToEncoderFactory.get("br");

        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("br", StreamEncoderFactories.BROTLI,
                                "x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isEqualTo(expectedBrotliFactory);
        // BROTLI is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY,
                                "gzip", StreamEncoderFactories.GZIP), headers
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
    }

    @Test
    void acceptEncodingSnappyFraming() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "x-snappy-framed");

        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY,
                                "gzip", StreamEncoderFactories.GZIP), headers
        )).isEqualTo(StreamEncoderFactories.SNAPPY);
        // SNAPPY is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                "deflate", StreamEncoderFactories.DEFLATE), headers
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
    }

    @Test
    void acceptEncodingAllOfThree() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "gzip, deflate, br");
        assertThat(HttpEncoders.determineEncoder(ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                                                 "deflate",
                                                                 StreamEncoderFactories.DEFLATE,
                                                                 "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // GZIP is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE,
                                "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // GZIP and BROTLI are not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // GZIP and DEFLATE are not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(allHeaderToEncoderFactory.get("br"));
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isNull();
    }

    @Test
    void acceptEncodingBoth() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate");
        assertThat(HttpEncoders.determineEncoder(ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                                                 "deflate",
                                                                 StreamEncoderFactories.DEFLATE), headers
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // Only GZIP is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("gzip", StreamEncoderFactories.GZIP), headers
        )).isEqualTo(StreamEncoderFactories.GZIP);

        // GZIP is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);

        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isNull();
    }

    @Test
    void acceptEncodingUnknown() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING, "piedpiper");
        // All are on the allow list.
        assertThat(HttpEncoders.determineEncoder(allHeaderToEncoderFactory, headers)).isNull();
    }

    @Test
    void acceptEncodingWithQualityValues() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "br;q=0.8, deflate, gzip;q=0.7, *;q=0.1");
        assertThat(HttpEncoders.determineEncoder(ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                                                 "deflate",
                                                                 StreamEncoderFactories.DEFLATE,
                                                                 "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // DEFLATE is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(
                allHeaderToEncoderFactory.get("br") != null ? StreamEncoderFactories.BROTLI
                                                            : StreamEncoderFactories.GZIP);
        // DEFLATE and BROTLI are not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("gzip", StreamEncoderFactories.GZIP), headers
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // SNAPPY is in the allow list and selected by wildcard.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isEqualTo(StreamEncoderFactories.SNAPPY);
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
    }

    @Test
    void acceptEncodingOrder() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "deflate;q=0.5, gzip;q=0.9, br;q=0.9");
        assertThat(HttpEncoders.determineEncoder(ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                                                 "deflate",
                                                                 StreamEncoderFactories.DEFLATE,
                                                                 "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(StreamEncoderFactories.GZIP);
        // GZIP is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE,
                                "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(StreamEncoderFactories.BROTLI);
        // GZIP and BROTLI are not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // SNAPPY is in the allow list and not selected as there is no wildcard.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isNull();
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
    }

    static Stream<Arguments> equivalentAcceptEncodingHeaders() {
        return Stream.of(
                // Leading comma, compact
                Arguments.of(
                        ",deflate;q=0.5,gzip;q=0.9,br;q=0.9",
                        "deflate;q=0.5, gzip;q=0.9, br;q=0.9"
                ),
                // Leading comma
                Arguments.of(
                        " , \t deflate;q=0.5,gzip;\t \tq=0.9, br  ; \t\t q=0.9",
                        "deflate;q=0.5, gzip;q=0.9, br;q=0.9"
                ),
                // Single encoding without quality value
                Arguments.of(
                        " , \t deflate \t",
                        "deflate"
                ),
                // Single encoding with quality value
                Arguments.of(
                        " , \t deflate ;     \t    q=0.5 \t",
                        "deflate;q=0.5"
                ),
                // Unknown encoding
                Arguments.of(
                        " , \t\t piedpiper,piperpied;q=0.999 \t",
                        "piedpiper,piperpied"
                )
        );
    }

    @Test
    void acceptEncodingWithZeroValues() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "gzip;q=0.0, br;q=0.0, *;q=0.1");

        assertThat(HttpEncoders.determineEncoder(ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                                                 "deflate",
                                                                 StreamEncoderFactories.DEFLATE,
                                                                 "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // GZIP is not in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("deflate", StreamEncoderFactories.DEFLATE,
                                "br", StreamEncoderFactories.BROTLI), headers
        )).isEqualTo(StreamEncoderFactories.DEFLATE);
        // SNAPPY and DEFLATE are in the allow list and both could be selected by wildcard.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY,
                                "deflate", StreamEncoderFactories.DEFLATE), headers
        )).isIn(StreamEncoderFactories.SNAPPY, StreamEncoderFactories.DEFLATE);
        // Only BROTLI is in the allow list but not selected as its quality value is 0.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("br", StreamEncoderFactories.BROTLI), headers
        )).isNull();
        // Only SNAPPY is in the allow list and selected by wildcard.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of("x-snappy-framed", StreamEncoderFactories.SNAPPY), headers
        )).isEqualTo(StreamEncoderFactories.SNAPPY);
        // None is in the allow list.
        assertThat(HttpEncoders.determineEncoder(
                ImmutableMap.of(), headers
        )).isNull();
    }

    @Test
    void acceptCustomEncoding() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "gzip;q=0.997, deflate=0.998, br=0.998," +
                                                         " piedpiper;q=0.999");

        final StreamEncoderFactory piedPiperFactory = customEncodingFactory("piedpiper");
        final Map<String, StreamEncoderFactory> encodingFactories =
                ImmutableMap.of("piedpiper", piedPiperFactory,
                                "gzip", StreamEncoderFactories.GZIP,
                                "deflate", StreamEncoderFactories.DEFLATE,
                                "br", StreamEncoderFactories.BROTLI);

        assertThat(HttpEncoders.determineEncoder(encodingFactories, headers)).isEqualTo(piedPiperFactory);
    }

    @Test
    void acceptCustomBrotliFactory() {
        final StreamEncoderFactory customBrotliFactory = customEncodingFactory("br");

        final Map<String, StreamEncoderFactory> encodingFactories =
                ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                "deflate", StreamEncoderFactories.DEFLATE,
                                "br", customBrotliFactory);

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.ACCEPT_ENCODING,
                                                         "br, gzip, deflate");

        assert HttpEncoders
                .determineEncoder(encodingFactories, headers)
                .equals(customBrotliFactory);
    }

    @ParameterizedTest
    @MethodSource("equivalentAcceptEncodingHeaders")
    void ignoresOWS(String firstAcceptEncodingHeader, String secondAcceptEncodingHeader) {
        final RequestHeaders firstHeaders = RequestHeaders.of(HttpMethod.GET, "/",
                                                              HttpHeaderNames.ACCEPT_ENCODING,
                                                              firstAcceptEncodingHeader);
        final RequestHeaders secondHeaders = RequestHeaders.of(HttpMethod.GET, "/",
                                                               HttpHeaderNames.ACCEPT_ENCODING,
                                                               secondAcceptEncodingHeader);

        final Map<String, StreamEncoderFactory> encodingFactories =
                ImmutableMap.of("gzip", StreamEncoderFactories.GZIP,
                                "deflate", StreamEncoderFactories.DEFLATE,
                                "br", StreamEncoderFactories.BROTLI);

        assertThat(HttpEncoders.determineEncoder(encodingFactories, firstHeaders)).isEqualTo(
                HttpEncoders.determineEncoder(encodingFactories, secondHeaders));
    }

    private static StreamEncoderFactory customEncodingFactory(String coding) {
        return new StreamEncoderFactory() {
            @Override
            public String encodingHeaderValue() {
                return coding;
            }

            @Override
            public OutputStream newEncoder(ByteBufOutputStream os) {
                fail();
                return null;
            }
        };
    }
}
