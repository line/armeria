/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging.structured;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.thrift.ApacheThriftCall;
import com.linecorp.armeria.common.thrift.ApacheThriftReply;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.util.TextFormatter;

/**
 * A representation and constructor of a service log which holds Apache Thrift based RPC invocation oriented
 * information.
 */
public class ApacheThriftStructuredLog extends StructuredLog {
    private static final Pattern thriftIfaceClassSuffixRegexp = Pattern.compile("\\.(?:Async)?Iface$");

    private final String thriftServiceName;
    private final String thriftMethodName;
    private final ApacheThriftCall thriftCall;
    private final ApacheThriftReply thriftReply;

    ApacheThriftStructuredLog(long timestampMillis,
                              long responseTimeNanos,
                              long requestSize,
                              long responseSize,
                              String thriftServiceName,
                              String thriftMethodName,
                              ApacheThriftCall thriftCall,
                              ApacheThriftReply thriftReply) {
        super(timestampMillis, responseTimeNanos, requestSize, responseSize);
        this.thriftServiceName = thriftServiceName;
        this.thriftMethodName = thriftMethodName;
        this.thriftCall = thriftCall;
        this.thriftReply = thriftReply;
    }

    /**
     * Constructs {@link ApacheThriftStructuredLog} from {@link RequestContext} and {@link ResponseLog}.
     * Can be used as {@link StructuredLogBuilder}.
     */
    public ApacheThriftStructuredLog(RequestContext reqCtx, ResponseLog resLog) {
        super(resLog);

        RequestLog req = resLog.request();

        RpcRequest rpcRequest = req.attr(RequestLog.RPC_REQUEST).get();
        if (rpcRequest == null) {
            // Request might be responded as error before reading arguments.
            thriftServiceName = null;
            thriftMethodName = null;
            thriftCall = null;
            thriftReply = null;
            return;
        }

        if (!(rpcRequest instanceof ThriftCall)) {
            throw new IllegalArgumentException(
                    "expected ThriftCall instance for " + RequestLog.RPC_REQUEST + " but was " + rpcRequest);
        }
        ThriftCall thriftCall = (ThriftCall) rpcRequest;

        thriftServiceName = thriftIfaceClassSuffixRegexp.matcher(thriftCall.serviceType().getCanonicalName())
                                                        .replaceAll("");

        thriftMethodName = thriftCall.method();
        this.thriftCall = (ApacheThriftCall) req.attr(RequestLog.RAW_RPC_REQUEST).get();
        thriftReply = (ApacheThriftReply) resLog.attr(ResponseLog.RAW_RPC_RESPONSE).get();
    }

    /**
     * Returns the fully qualified Thrift service name which is associated to the log.
     *
     * @return fully qualified Thrift service name
     */
    @JsonProperty
    public String thriftServiceName() {
        return thriftServiceName;
    }

    /**
     * Returns the Thrift method name which was called in the context of the log.
     *
     * @return Thrift method name
     */
    @JsonProperty
    public String thriftMethodName() {
        return thriftMethodName;
    }

    /**
     * Returns the {@link ApacheThriftCall} object which includes Thrift call information of the log.
     *
     * @return an instance of {@link ApacheThriftCall} which is associated to the log
     */
    @JsonProperty
    public ApacheThriftCall thriftCall() {
        return thriftCall;
    }

    /**
     * Returns the {@link ApacheThriftReply} object which includes Thrift reply information of the log.
     *
     * @return an instance of {@link ApacheThriftReply} which is associated to the log
     */
    @JsonProperty
    public ApacheThriftReply thriftReply() {
        return thriftReply;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("timestampMillis", timestampMillis() +
                                                  '(' + TextFormatter.epoch(timestampMillis()) + ')')
                          .add("responseTimeNanos", String.valueOf(responseTimeNanos()) +
                                                    '(' + TextFormatter.elapsed(responseTimeNanos()) + ')')
                          .add("requestSize", TextFormatter.size(requestSize()))
                          .add("responseSize", TextFormatter.size(responseSize()))
                          .add("thriftServiceName", thriftServiceName)
                          .add("thriftMethodName", thriftMethodName)
                          .add("thriftCall", thriftCall)
                          .add("thriftReply", thriftReply)
                          .toString();
    }
}
