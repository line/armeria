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

package com.linecorp.armeria.server.tomcat;

import java.io.IOException;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ActionHook;
import org.apache.coyote.Adapter;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.juli.logging.Log;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * Provides a fake Processor to provide {@link ActionHook} to request/response.
 */
final class ArmeriaProcessor extends AbstractProcessor {

    private static final Log log = new LogWrapper(ArmeriaProcessor.class);

    /**
     * Create a new instance.
     */
    ArmeriaProcessor(Adapter adapter) {
        super(adapter);
    }

    @Override
    protected void prepareResponse() throws IOException {}

    @Override
    protected void finishResponse() throws IOException {}

    @Override
    protected void ack(ContinueResponseTiming continueResponseTiming) {}

    @Override
    protected void flush() throws IOException {}

    @Override
    protected int available(boolean doRead) {
        return getRequest().getInputBuffer().available();
    }

    @Override
    protected void setRequestBody(ByteChunk body) {}

    @Override
    protected void setSwallowResponse() {}

    @Override
    protected void disableSwallowRequest() {}

    @Override
    protected boolean isRequestBodyFullyRead() {
        return false;
    }

    @Override
    protected void registerReadInterest() {}

    @Override
    protected boolean isReadyForWrite() {
        return false;
    }

    @Override
    protected boolean isTrailerFieldsReady() {
        return false;
    }

    @Override
    protected boolean flushBufferedWrite() throws IOException {
        return false;
    }

    @Override
    protected AbstractEndpoint.Handler.SocketState dispatchEndRequest() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected AbstractEndpoint.Handler.SocketState service(SocketWrapperBase<?> socketWrapper)
            throws IOException {
        // Doesn't seem to be used.
        return null;
    }

    @Override
    protected Log getLog() {
        return log;
    }

    @Override
    public void pause() {}
}
