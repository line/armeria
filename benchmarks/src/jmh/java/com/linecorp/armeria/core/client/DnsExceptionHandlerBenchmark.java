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

package com.linecorp.armeria.core.client;

import java.util.regex.Pattern;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmarks of an exception handler conducted when DNS query is failed.
 *
 * @see <a href="https://github.com/netty/netty/blob/4.1/resolver-dns/src/main/java/io/netty/resolver/dns/DnsResolveContext.java">DnsResolveContext</a>
 */
public class DnsExceptionHandlerBenchmark {

    private static final RuntimeException NXDOMAIN_QUERY_FAILED_EXCEPTION =
            new RuntimeException("No answer found and NXDOMAIN response code returned");
    private static final RuntimeException CNAME_NOT_FOUND_QUERY_FAILED_EXCEPTION =
            new RuntimeException("No matching CNAME record found");
    private static final RuntimeException NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION =
            new RuntimeException("No matching record type found");
    private static final RuntimeException UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION =
            new RuntimeException("Response type was unrecognized");
    private static final RuntimeException NAME_SERVERS_EXHAUSTED_EXCEPTION =
            new RuntimeException("No name servers returned an answer");

    private static final Pattern NXDOMAIN_EXCEPTION = Pattern.compile("\\bNXDOMAIN\\b");
    private static final Pattern CNAME_EXCEPTION = Pattern.compile("\\bCNAME\\b");
    private static final Pattern NO_MATCHING_EXCEPTION = Pattern.compile("\\bmatching\\b");
    private static final Pattern UNRECOGNIZED_TYPE_EXCEPTION = Pattern.compile("\\bunrecognized\\b");
    private static final Pattern NO_NS_RETURNED_EXCEPTION = Pattern.compile("\\bservers returned an answer\\b");

    @Benchmark
    public void nxdomain_pattern(Blackhole bh) throws ClassNotFoundException {
        bh.consume(NXDOMAIN_EXCEPTION.matcher(NXDOMAIN_QUERY_FAILED_EXCEPTION.getMessage()).find());
    }

    @Benchmark
    public void nxdomain_contains(Blackhole bh) throws ClassNotFoundException {
        bh.consume(NXDOMAIN_QUERY_FAILED_EXCEPTION.getMessage().contains("NXDOMAIN"));
    }

    @Benchmark
    public void cname_pattern(Blackhole bh) throws ClassNotFoundException {
        bh.consume(CNAME_EXCEPTION.matcher(CNAME_NOT_FOUND_QUERY_FAILED_EXCEPTION.getMessage()).find());
    }

    @Benchmark
    public void cname_contains(Blackhole bh) throws ClassNotFoundException {
        bh.consume(CNAME_NOT_FOUND_QUERY_FAILED_EXCEPTION.getMessage().contains("CNAME"));
    }

    @Benchmark
    public void noMatching_pattern(Blackhole bh) throws ClassNotFoundException {
        bh.consume(NO_MATCHING_EXCEPTION.matcher(NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION.getMessage())
                                        .find());
    }

    @Benchmark
    public void noMatching_contains(Blackhole bh) throws ClassNotFoundException {
        bh.consume(NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION.getMessage().contains("No matching record"));
    }

    @Benchmark
    public void noMatching_startsWith(Blackhole bh) throws ClassNotFoundException {
        bh.consume(NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION.getMessage().startsWith("No matching record"));
    }

    @Benchmark
    public void unrecognized_pattern(Blackhole bh) throws ClassNotFoundException {
        bh.consume(UNRECOGNIZED_TYPE_EXCEPTION.matcher(UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION.getMessage())
                                              .find());
    }

    @Benchmark
    public void unrecognized_contains(Blackhole bh) throws ClassNotFoundException {
        bh.consume(UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION.getMessage().contains("unrecognized"));
    }

    @Benchmark
    public void unrecognized_endsWith(Blackhole bh) throws ClassNotFoundException {
        bh.consume(UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION.getMessage().endsWith("unrecognized"));
    }

    @Benchmark
    public void exhausted_pattern(Blackhole bh) throws ClassNotFoundException {
        bh.consume(NO_NS_RETURNED_EXCEPTION.matcher(NAME_SERVERS_EXHAUSTED_EXCEPTION.getMessage()).find());
    }

    @Benchmark
    public void exhausted_contains(Blackhole bh) throws ClassNotFoundException {
        bh.consume(NAME_SERVERS_EXHAUSTED_EXCEPTION.getMessage().contains("No name servers"));
    }
}
