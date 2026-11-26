/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.common.ArmeriaHttp2HeadersDecoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameListenerDecorator;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;

class Http2ResponseDecoderTest {

    private enum Frame {
        HEADERS, INFORMATIONAL, TRAILERS, DATA
    }

    static Stream<Arguments> cancellationFrames() {
        return Stream.of(Frame.values()).flatMap(frame -> Stream.of(false, true)
                .filter(endOfStream -> frame != Frame.INFORMATIONAL || !endOfStream)
                .filter(endOfStream -> frame != Frame.TRAILERS || endOfStream)
                .map(endOfStream -> Arguments.of(frame, endOfStream)));
    }

    @ParameterizedTest
    @MethodSource("cancellationFrames")
    void cancellationDuringWriteOnlyResetsItsStream(Frame frame, boolean endOfStream) throws Exception {
        try (Fixture fixture = new Fixture()) {
            final Response cancelled = fixture.newResponse();
            final Response victim = fixture.newResponse();
            final CompletableFuture<AggregatedHttpResponse> victimFuture = victim.response.aggregate();
            ByteBuf queuedData = null;
            if (frame == Frame.DATA || frame == Frame.TRAILERS) {
                fixture.headers(cancelled.id, "200", false);
                fixture.exchange();
                fixture.data(cancelled.id, 8192, 0, false);
                fixture.exchange();
                assertThat(fixture.decoder.inboundTrafficController().get()).isEqualTo(8192);
                queuedData = fixture.lastData;
                assertThat(queuedData).isNotNull();
                assertThat(queuedData.refCnt()).isPositive();
            }
            cancelled.cancelOnWrite(frame);
            if (frame == Frame.DATA) {
                fixture.data(cancelled.id, 1024, 7, endOfStream);
            } else if (frame == Frame.TRAILERS) {
                fixture.peer.encoder().writeHeaders(fixture.peerContext(), cancelled.id,
                        new DefaultHttp2Headers().add("trailer", "value"), 0, true,
                        fixture.peerContext().newPromise());
            } else {
                fixture.headers(cancelled.id, frame == Frame.INFORMATIONAL ? "100" : "200", endOfStream);
            }
            fixture.headers(victim.id, "200", false);
            fixture.data(victim.id, 16, 0, true);
            fixture.exchange();

            assertThat(fixture.client.isActive()).isTrue();
            assertThat(victimFuture.get(5, TimeUnit.SECONDS).content().length()).isEqualTo(16);
            assertThat(fixture.failure).isInstanceOf(Http2Exception.StreamException.class);
            assertThat(((Http2Exception.StreamException) fixture.failure).streamId()).isEqualTo(cancelled.id);
            assertThat(fixture.failure.error()).isEqualTo(Http2Error.INTERNAL_ERROR);
            assertThat(cancelled.response.whenComplete()).isCompletedExceptionally();
            assertThat(cancelled.context.log().whenComplete().get(5, TimeUnit.SECONDS).responseCause())
                    .isSameAs(cancelled.cause);
            assertThat(fixture.decoder.getResponse((cancelled.id - 1) >>> 1)).isNull();
            assertThat(fixture.handler.connection().numActiveStreams()).isZero();
            assertThat(fixture.closedStreams).containsOnlyOnce(cancelled.id);
            assertThat(fixture.decoder.hasUnfinishedResponses()).isFalse();
            assertThat(fixture.decoder.inboundTrafficController().get()).isZero();
            assertThat(fixture.handler.decoder().flowController().unconsumedBytes(
                    fixture.handler.connection().connectionStream())).isZero();
            if (queuedData != null) {
                assertThat(queuedData.refCnt()).isZero();
            }
            if (frame == Frame.DATA) {
                assertThat(fixture.receivedData).isNotNull();
                assertThat(fixture.receivedData.refCnt()).isZero();
            }
            assertThat(fixture.goAways).isZero();
            assertThat(fixture.resets).contains(cancelled.id);

            final Response subsequent = fixture.newResponse();
            final CompletableFuture<AggregatedHttpResponse> subsequentFuture = subsequent.response.aggregate();
            fixture.headers(subsequent.id, "200", false);
            fixture.data(subsequent.id, 131072, 0, true);
            fixture.exchange();
            assertThat(subsequentFuture.get(5, TimeUnit.SECONDS).content().length()).isEqualTo(131072);
            assertThat(fixture.client.isActive()).isTrue();
        }
    }

    @Test
    void streamSlotIsReturnedAfterCancellation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final Response cancelled = fixture.newResponse();
            cancelled.cancelOnWrite(Frame.HEADERS);
            fixture.headers(cancelled.id, "200", true);
            fixture.exchange();
            assertThat(fixture.client.isActive()).isTrue();
            assertThat(fixture.decoder.reserveUnfinishedResponse(1)).isTrue();
            fixture.decoder.decrementUnfinishedResponses();
            final Response next = fixture.newResponse();
            final CompletableFuture<AggregatedHttpResponse> future = next.response.aggregate();
            fixture.headers(next.id, "200", true);
            fixture.exchange();
            assertThat(future.get(5, TimeUnit.SECONDS).status().code()).isEqualTo(200);
            assertThat(fixture.decoder.hasUnfinishedResponses()).isFalse();
        }
    }

    private static final class Response {
        final int id;
        final DecodedHttpResponse response;
        final ClientRequestContext context;
        final RequestLogBuilder log;
        final RequestLogBuilder realLog;
        final IllegalStateException cause = new IllegalStateException("cancelled response");

        Response(int id, DecodedHttpResponse response, ClientRequestContext context, RequestLogBuilder log,
                 RequestLogBuilder realLog) {
            this.id = id;
            this.response = response;
            this.context = context;
            this.log = log;
            this.realLog = realLog;
        }

        void cancelOnWrite(Frame frame) {
            final Runnable cancel = () -> CompletableFuture.runAsync(() -> response.abort(cause)).join();
            if (frame == Frame.DATA) {
                doAnswer(invocation -> {
                    realLog.increaseResponseLength((HttpData) invocation.getArgument(0));
                    cancel.run();
                    return null;
                }).when(log).increaseResponseLength(any(HttpData.class));
            } else if (frame == Frame.TRAILERS) {
                doAnswer(invocation -> {
                    realLog.defer(RequestLogProperty.RESPONSE_TRAILERS);
                    cancel.run();
                    return null;
                }).when(log).defer(RequestLogProperty.RESPONSE_TRAILERS);
            } else {
                doAnswer(invocation -> {
                    realLog.startResponse();
                    cancel.run();
                    return null;
                }).when(log).startResponse();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ClientFactory factory = ClientFactory.builder().idleTimeoutMillis(0).build();
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel server = new EmbeddedChannel();
        final EventLoop eventLoop = mock(EventLoop.class, delegatesTo(client.eventLoop()));
        final Channel decoderChannel = mock(Channel.class, invocation -> {
            if (invocation.getMethod().getName().equals("eventLoop")) {
                return eventLoop;
            }
            return delegatesTo(client).answer(invocation);
        });
        final Http2ClientConnectionHandler handler;
        final Http2ConnectionHandler peer;
        final Http2ResponseDecoder decoder;
        final List<Integer> resets = new ArrayList<>();
        final List<Integer> closedStreams = new ArrayList<>();
        int goAways;
        @Nullable
        Http2Exception failure;
        @Nullable
        ByteBuf receivedData;
        @Nullable
        ByteBuf lastData;

        Fixture() {
            // EmbeddedEventLoop considers every thread to be an event-loop thread.
            final Thread eventLoopThread = Thread.currentThread();
            when(eventLoop.inEventLoop()).thenAnswer(unused -> Thread.currentThread() == eventLoopThread);
            final Http2Connection connection = new DefaultHttp2Connection(false);
            connection.addListener(new Http2ConnectionAdapter() {
                @Override
                public void onStreamClosed(Http2Stream stream) {
                    closedStreams.add(stream.id());
                }
            });
            final Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(
                    connection, new DefaultHttp2FrameWriter() {
                        @Override
                        public ChannelFuture writeRstStream(ChannelHandlerContext ctx, int streamId,
                                                            long errorCode, ChannelPromise promise) {
                            resets.add(streamId);
                            return super.writeRstStream(ctx, streamId, errorCode, promise);
                        }
                    });
            handler = new Http2ClientConnectionHandlerBuilder(
                    decoderChannel, (HttpClientFactory) factory.unwrap(), SessionProtocol.H2C)
                    .codec(new DefaultHttp2ConnectionDecoder(connection, encoder,
                            new DefaultHttp2FrameReader(new ArmeriaHttp2HeadersDecoder(false, 8192))), encoder)
                    .build();
            decoder = handler.responseDecoder();
            handler.decoder().frameListener(new Http2FrameListenerDecorator(decoder) {
                @Override
                public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                                          int streamDependency, short weight, boolean exclusive, int padding,
                                          boolean endOfStream) throws Http2Exception {
                    try {
                        super.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive,
                                            padding, endOfStream);
                    } catch (Http2Exception e) {
                        failure = e;
                        throw e;
                    }
                }

                @Override
                public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
                                      boolean endOfStream) throws Http2Exception {
                    lastData = data;
                    try {
                        return super.onDataRead(ctx, streamId, data, padding, endOfStream);
                    } catch (Http2Exception e) {
                        receivedData = data;
                        failure = e;
                        throw e;
                    }
                }
            });
            peer = new Http2ConnectionHandlerBuilder().server(true).frameListener(new Http2FrameAdapter() {
                @Override
                public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode,
                                         ByteBuf debugData) {
                    goAways++;
                }
            }).build();
            server.pipeline().addLast(peer);
            client.pipeline().addLast(handler);
            final HttpSession session =
                    mock(HttpSession.class, withSettings().extraInterfaces(ChannelHandler.class));
            when(session.isAcquirable(any())).thenReturn(true);
            client.pipeline().addLast((ChannelHandler) session);
            exchange();
        }

        Response newResponse() {
            final int id = handler.connection().local().incrementAndGetNextStreamId();
            final DecodedHttpResponse response = new DecodedHttpResponse(eventLoop);
            response.setStreamId(id);
            response.init(decoder.inboundTrafficController());
            final ClientRequestContext base = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                    .eventLoop(eventLoop).sessionProtocol(SessionProtocol.H2C).build();
            base.logBuilder().endRequest();
            final RequestLogBuilder log = mock(RequestLogBuilder.class, delegatesTo(base.logBuilder()));
            final ClientRequestContext context = new ClientRequestContextWrapper(base) {
                @Override
                public RequestLogBuilder logBuilder() {
                    return log;
                }
            };
            assertThat(decoder.reserveUnfinishedResponse(Integer.MAX_VALUE)).isTrue();
            final HttpResponseWrapper wrapper = decoder.addResponse(null, (id - 1) >>> 1, response,
                                                                    context, eventLoop);
            wrapper.initTimeout();
            final ChannelHandlerContext ctx = client.pipeline().context(handler);
            handler.encoder().writeHeaders(ctx, id, new DefaultHttp2Headers().method("GET").scheme("http")
                    .authority("localhost").path("/"), 0, true, ctx.newPromise());
            exchange();
            return new Response(id, response, context, log, base.logBuilder());
        }

        ChannelHandlerContext peerContext() {
            return server.pipeline().context(peer);
        }

        void headers(int id, String status, boolean endOfStream) {
            peer.encoder().writeHeaders(peerContext(), id, new DefaultHttp2Headers().status(status),
                                       0, endOfStream, peerContext().newPromise());
        }

        void data(int id, int length, int padding, boolean endOfStream) {
            peer.encoder().writeData(peerContext(), id, Unpooled.buffer(length).writeZero(length), padding,
                                     endOfStream, peerContext().newPromise());
        }

        void exchange() {
            for (int i = 0; i < 100; i++) {
                client.runPendingTasks();
                server.runPendingTasks();
                if (client.isOpen()) {
                    client.flushOutbound();
                }
                if (server.isOpen()) {
                    server.flushOutbound();
                }
                final boolean clientSent = transfer(client, server);
                final boolean serverSent = transfer(server, client);
                if (!clientSent && !serverSent) {
                    return;
                }
            }
            throw new AssertionError("HTTP/2 frame exchange did not finish");
        }

        private static boolean transfer(EmbeddedChannel from, EmbeddedChannel to) {
            final List<ByteBuf> frames = new ArrayList<>();
            ByteBuf frame;
            while ((frame = from.readOutbound()) != null) {
                frames.add(frame);
            }
            if (frames.isEmpty()) {
                return false;
            }
            final ByteBuf input = Unpooled.wrappedBuffer(frames.toArray(new ByteBuf[0]));
            if (to.isOpen()) {
                to.writeInbound(input);
            } else {
                input.release();
            }
            return true;
        }

        @Override
        public void close() {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
            factory.close();
        }
    }
}
