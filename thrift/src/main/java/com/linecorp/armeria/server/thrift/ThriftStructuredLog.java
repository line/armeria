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

package com.linecorp.armeria.server.thrift;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.server.logging.structured.StructuredLog;
import com.linecorp.armeria.server.logging.structured.StructuredLogBuilder;

/**
 * A representation and constructor of a service log which holds Apache Thrift based RPC invocation oriented
 * information.
 */
public class ThriftStructuredLog extends StructuredLog {

    private final String thriftServiceName;
    private final String thriftMethodName;
    private final ThriftCall thriftCall;
    private final ThriftReply thriftReply;

    ThriftStructuredLog(long timestampMillis,
                        long responseTimeNanos,
                        long requestSize,
                        long responseSize,
                        String thriftServiceName,
                        String thriftMethodName,
                        ThriftCall thriftCall,
                        ThriftReply thriftReply) {
        super(timestampMillis, responseTimeNanos, requestSize, responseSize);
        this.thriftServiceName = thriftServiceName;
        this.thriftMethodName = thriftMethodName;
        this.thriftCall = thriftCall;
        this.thriftReply = thriftReply;
    }

    /**
     * Constructs {@link ThriftStructuredLog} from {@link RequestContext} and {@link RequestLog}.
     * Can be used as {@link StructuredLogBuilder}.
     */
    public ThriftStructuredLog(RequestLog reqLog) {
        super(reqLog);

        Object requestContent = reqLog.rawRequestContent();
        if (requestContent == null) {
            // Request might be responded as error before reading arguments.
            thriftServiceName = null;
            thriftMethodName = null;
            thriftCall = null;
            thriftReply = null;
            return;
        }

        if (!(requestContent instanceof ThriftCall)) {
            throw new IllegalArgumentException(
                    "expected ApacheThriftCall instance for RequestLog.requestContent() but was " +
                    requestContent);
        }

        final ThriftCall thriftCall = (ThriftCall) requestContent;

        // Get the service name from the args class name.
        final String argsTypeName = thriftCall.args().getClass().getName();
        thriftServiceName = argsTypeName.substring(0, argsTypeName.indexOf('$'));

        thriftMethodName = thriftCall.header().name;
        this.thriftCall = thriftCall;
        thriftReply = (ThriftReply) reqLog.rawResponseContent();
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
     * Returns the {@link ThriftCall} object which includes Thrift call information of the log.
     *
     * @return an instance of {@link ThriftCall} which is associated to the log
     */
    @JsonProperty
    public ThriftCall thriftCall() {
        return thriftCall;
    }

    /**
     * Returns the {@link ThriftReply} object which includes Thrift reply information of the log.
     *
     * @return an instance of {@link ThriftReply} which is associated to the log
     */
    @JsonProperty
    public ThriftReply thriftReply() {
        return thriftReply;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("timestamp", TextFormatter.epoch(timestampMillis()))
                          .add("responseTime", TextFormatter.elapsed(responseTimeNanos()))
                          .add("requestSize", TextFormatter.size(requestSize()))
                          .add("responseSize", TextFormatter.size(responseSize()))
                          .add("thriftServiceName", thriftServiceName)
                          .add("thriftMethodName", thriftMethodName)
                          .add("thriftCall", thriftCall)
                          .add("thriftReply", thriftReply)
                          .toString();
    }
}
