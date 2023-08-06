/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.grpc.GrpcServiceBuilder.toGrpcStatusFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.internal.server.annotation.DecoratorAnnotationUtil.DecoratorAndOrder;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecoratorFactoryFunction;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A1Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A2Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A3Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.B1Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.B2Exception;

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.MetricsServiceGrpc.MetricsServiceImplBase;
import testing.grpc.ReconnectServiceGrpc.ReconnectServiceImplBase;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcServiceBuilderTest {

    private static final Metadata.Key<String> TEST_KEY =
            Metadata.Key.of("test_key", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> TEST_KEY2 =
            Metadata.Key.of("test_key2", Metadata.ASCII_STRING_MARSHALLER);

    private final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void mixExceptionMappingAndGrpcStatusFunction() {
        assertThatThrownBy(() -> GrpcService.builder()
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED)
                                            .exceptionMapping(
                                                    (ctx, cause, metadata) -> Status.PERMISSION_DENIED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceptionMapping() and addExceptionMapping() are mutually exclusive.");

        assertThatThrownBy(() -> GrpcService.builder()
                                            .exceptionMapping(
                                                    (ctx, cause, metadata) -> Status.PERMISSION_DENIED)
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("addExceptionMapping() and exceptionMapping() are mutually exclusive.");
    }

    @Test
    void duplicatedExceptionMappings() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class,
                                               (ctx, throwable, metadata) -> Status.RESOURCE_EXHAUSTED);

        assertThatThrownBy(() -> {
            GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class,
                                                   (ctx, throwable, metadata) -> Status.UNIMPLEMENTED);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");

        assertThatThrownBy(() -> {
            GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class,
                                                   (ctx, throwable, metadata) -> Status.UNIMPLEMENTED);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");
    }

    @Test
    void sortExceptionMappings() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class,
                                               (ctx, throwable, metadata) -> Status.RESOURCE_EXHAUSTED);
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A2Exception.class,
                                               (ctx, throwable, metadata) -> Status.UNIMPLEMENTED);

        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A2Exception.class, A1Exception.class);

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B1Exception.class,
                                               (ctx, throwable, metadata) -> Status.UNAUTHENTICATED);
        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A2Exception.class,
                                 A1Exception.class,
                                 B1Exception.class);

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A3Exception.class,
                                               (ctx, throwable, metadata) -> Status.UNAUTHENTICATED
        );
        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A3Exception.class,
                                 A2Exception.class,
                                 A1Exception.class,
                                 B1Exception.class);

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B2Exception.class,
                                               (ctx, throwable, metadata) -> Status.NOT_FOUND);
        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A3Exception.class,
                                 A2Exception.class,
                                 A1Exception.class,
                                 B2Exception.class,
                                 B1Exception.class);

        final GrpcStatusFunction statusFunction = toGrpcStatusFunction(exceptionMappings);

        Status status = GrpcStatus.fromThrowable(statusFunction, ctx, new A3Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.UNAUTHENTICATED);

        status = GrpcStatus.fromThrowable(statusFunction, ctx, new A2Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.UNIMPLEMENTED);

        status = GrpcStatus.fromThrowable(statusFunction, ctx, new A1Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);

        status = GrpcStatus.fromThrowable(statusFunction, ctx, new B2Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.NOT_FOUND);

        status = GrpcStatus.fromThrowable(statusFunction, ctx, new B1Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.UNAUTHENTICATED);
    }

    @Test
    void mapStatus() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A2Exception.class,
                                               (ctx, throwable, metadata) -> Status.PERMISSION_DENIED);
        final GrpcStatusFunction statusFunction = toGrpcStatusFunction(exceptionMappings);

        for (Throwable ex : ImmutableList.of(new A2Exception(), new A3Exception())) {
            final Status status = Status.UNKNOWN.withCause(ex);
            final Metadata metadata = new Metadata();
            final Status newStatus = GrpcStatus.fromStatusFunction(statusFunction, ctx, status, metadata);
            assertThat(newStatus.getCode()).isEqualTo(Code.PERMISSION_DENIED);
            assertThat(newStatus.getCause()).isEqualTo(ex);
            assertThat(metadata.keys()).isEmpty();
        }

        final Status status = Status.DEADLINE_EXCEEDED.withCause(new A1Exception());
        final Metadata metadata = new Metadata();
        final Status newStatus = GrpcStatus.fromStatusFunction(statusFunction, ctx, status, metadata);
        assertThat(newStatus).isSameAs(status);
        assertThat(metadata.keys()).isEmpty();
    }

    @Test
    void mapStatusAndMetadata() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B1Exception.class,
                                               (ctx, throwable, metadata) -> {
                                                   metadata.put(TEST_KEY, throwable.getClass().getSimpleName());
                                                   return Status.ABORTED;
                                               });
        final GrpcStatusFunction statusFunction = toGrpcStatusFunction(exceptionMappings);

        final Status status = Status.UNKNOWN.withCause(new B1Exception());

        final Metadata metadata1 = new Metadata();
        final Status newStatus1 = GrpcStatus.fromStatusFunction(statusFunction, ctx, status, metadata1);
        assertThat(newStatus1.getCode()).isEqualTo(Code.ABORTED);
        assertThat(metadata1.get(TEST_KEY)).isEqualTo("B1Exception");
        assertThat(metadata1.keys()).containsOnly(TEST_KEY.name());

        final Metadata metadata2 = new Metadata();
        metadata2.put(TEST_KEY2, "test");
        final Status newStatus2 = GrpcStatus.fromStatusFunction(statusFunction, ctx, status, metadata2);
        assertThat(newStatus2.getCode()).isEqualTo(Code.ABORTED);
        assertThat(metadata2.get(TEST_KEY)).isEqualTo("B1Exception");
        assertThat(metadata2.keys()).containsOnly(TEST_KEY.name(), TEST_KEY2.name());
    }

    @Test
    void addServices() {
        final BindableService metricsService = new MetricsServiceImpl();
        final BindableService reconnectService = new ReconnectServiceImpl();

        final List<String> serviceNames =
                ImmutableList.of("armeria.grpc.testing.MetricsService",
                                 "armeria.grpc.testing.ReconnectService");

        final GrpcService grpcService1 =
                GrpcService.builder().addServices(metricsService, reconnectService).build();

        final GrpcService grpcService2 =
                GrpcService.builder().addServices(ImmutableList.of(metricsService, reconnectService)).build();

        final GrpcService grpcService3 =
                GrpcService.builder()
                           .addServiceDefinitions(
                                   ServerInterceptors.intercept(metricsService, new DummyInterceptor()),
                                   ServerInterceptors.intercept(reconnectService, new DummyInterceptor())
                           )
                           .build();

        final GrpcService grpcService4 =
                GrpcService
                        .builder()
                        .addServiceDefinitions(
                                ImmutableList.of(
                                        ServerInterceptors.intercept(metricsService, new DummyInterceptor()),
                                        ServerInterceptors.intercept(reconnectService, new DummyInterceptor())
                                )
                        )
                        .build();

        for (GrpcService s : ImmutableList.of(grpcService1, grpcService2, grpcService3, grpcService4)) {
            assertThat(s.services().stream().map(it -> it.getServiceDescriptor().getName()))
                    .containsExactlyInAnyOrderElementsOf(serviceNames);
        }
    }

    @Test
    void cannotSetUnframedErrorHandlerIfDisabledUnframedRequests() {
        assertThatThrownBy(() -> GrpcService.builder()
                                            .enableUnframedRequests(false)
                                            .unframedGrpcErrorHandler(UnframedGrpcErrorHandler.of())
                                            .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "'unframedGrpcErrorHandler' can only be set if unframed requests are enabled");
    }

    @Test
    void addServiceWithDecorators() {
        final FirstTestServiceImpl firstTestService = new FirstTestServiceImpl();
        final SecondTestServiceImpl secondTestService = new SecondTestServiceImpl();

        final GrpcServiceBuilder builder = GrpcService.builder()
                                                      .addService(firstTestService)
                                                      .addService("/foo", secondTestService);
        final HandlerRegistry handlerRegistry = handlerRegistry(builder);
        final Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> decorators =
                handlerRegistry.annotationDecorators();

        ServerMethodDefinition<?, ?> methodDefinition = handlerRegistry.methods().get(
                "armeria.grpc.testing.TestService/UnaryCall");
        List<DecoratorAndOrder> decoratorAndOrders = decorators.get(methodDefinition);
        assertThat(values(decoratorAndOrders))
                .containsExactly(Decorator1.class,
                                 Decorator2.class,
                                 LoggingDecoratorFactoryFunction.class);

        methodDefinition = handlerRegistry.methods().get("foo/EmptyCall");
        decoratorAndOrders = decorators.get(methodDefinition);
        assertThat(values(decoratorAndOrders))
                .containsExactly(Decorator1.class,
                                 Decorator2.class);

        methodDefinition = handlerRegistry.methods().get("foo/UnaryCall");
        decoratorAndOrders = decorators.get(methodDefinition);
        assertThat(values(decoratorAndOrders))
                .containsExactly(Decorator1.class,
                                 Decorator3.class);
    }

    private static HandlerRegistry handlerRegistry(GrpcServiceBuilder builder) {
        final GrpcService grpcService = builder.build();
        assertThat(grpcService).isInstanceOf(GrpcDecoratingService.class);
        final GrpcDecoratingService decoratingService = (GrpcDecoratingService) grpcService;
        return decoratingService.handlerRegistry();
    }

    @Test
    void addServiceWithHierarchicalDecorators() {
        final ThirdTestServiceImpl thirdTestService = new ThirdTestServiceImpl();

        final GrpcServiceBuilder builder = GrpcService.builder()
                                                      .addService(thirdTestService);
        final HandlerRegistry handlerRegistry = handlerRegistry(builder);
        final Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> decorators =
                handlerRegistry.annotationDecorators();

        ServerMethodDefinition<?, ?> methodDefinition = handlerRegistry.methods().get(
                "armeria.grpc.testing.TestService/UnaryCall");
        List<DecoratorAndOrder> decoratorAndOrders = decorators.get(methodDefinition);
        assertThat(values(decoratorAndOrders))
                .containsExactly(Decorator1.class, Decorator2.class);

        methodDefinition = handlerRegistry.methods().get("armeria.grpc.testing.TestService/EmptyCall");
        decoratorAndOrders = decorators.get(methodDefinition);
        assertThat(values(decoratorAndOrders))
                .containsExactly(Decorator1.class, Decorator3.class);
    }

    @Test
    void addServiceWithInterceptorAndDecorators() {
        final FirstTestServiceImpl firstTestService = new FirstTestServiceImpl();
        final GrpcServiceBuilder builder = GrpcService
                .builder()
                .addService(firstTestService,
                            impl -> ServerInterceptors.intercept(impl, new DummyInterceptor()));
        final HandlerRegistry handlerRegistry = handlerRegistry(builder);
        final Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> decorators =
                handlerRegistry.annotationDecorators();

        final ServerMethodDefinition<?, ?> methodDefinition = handlerRegistry.methods().get(
                "armeria.grpc.testing.TestService/UnaryCall");
        final List<DecoratorAndOrder> decoratorAndOrders = decorators.get(methodDefinition);
        assertThat(values(decoratorAndOrders))
                .containsExactly(Decorator1.class,
                                 Decorator2.class,
                                 LoggingDecoratorFactoryFunction.class);
    }

    @Test
    void setGrpcHealthCheckService() {
        final GrpcService grpcService =
                GrpcService.builder()
                           .addService(GrpcHealthCheckService.builder().build())
                           .build();
        assertThat(grpcService.services().stream().map(it -> it.getServiceDescriptor().getName()))
                .containsExactlyInAnyOrderElementsOf(ImmutableList.of("grpc.health.v1.Health"));
    }

    @Test
    void enableDefaultGrpcHealthCheckService() {
        final GrpcService grpcService =
                GrpcService.builder()
                           .enableHealthCheckService(true)
                           .build();
        assertThat(grpcService.services().stream().map(it -> it.getServiceDescriptor().getName()))
                .containsExactlyInAnyOrderElementsOf(ImmutableList.of("grpc.health.v1.Health"));
    }

    @Test
    void illegalStateOfGrpcHealthCheckService() {
        assertThatThrownBy(() -> GrpcService.builder()
                                            .addService(GrpcHealthCheckService.builder().build())
                                            .enableHealthCheckService(true)
                                            .build())
                .isInstanceOf(IllegalStateException.class);
    }

    private static class MetricsServiceImpl extends MetricsServiceImplBase {}

    private static class ReconnectServiceImpl extends ReconnectServiceImplBase {}

    private static class DummyInterceptor implements ServerInterceptor {
        @Override
        public <REQ, RESP> Listener<REQ> interceptCall(ServerCall<REQ, RESP> call, Metadata headers,
                                                       ServerCallHandler<REQ, RESP> next) {
            return next.startCall(call, headers);
        }
    }

    private static class Decorator1 implements DecoratingHttpServiceFunction {

        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            return delegate.serve(ctx, req);
        }
    }

    private static class Decorator2 implements DecoratingHttpServiceFunction {

        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            return delegate.serve(ctx, req);
        }
    }

    private static class Decorator3 implements DecoratingHttpServiceFunction {

        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            return delegate.serve(ctx, req);
        }
    }

    @Decorator(Decorator1.class)
    @Decorator(Decorator2.class)
    @LoggingDecorator(requestLogLevel = LogLevel.INFO)
    private static class FirstTestServiceImpl extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Decorator(Decorator1.class)
    private static class SecondTestServiceImpl extends TestServiceImplBase {

        @Override
        @Decorator(Decorator2.class)
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        @Decorator(Decorator3.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Decorator(Decorator1.class)
    private abstract static class AbstractThirdTestServiceImpl extends TestServiceImplBase {

        @Override
        @Decorator(Decorator2.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    private static class ThirdTestServiceImpl extends AbstractThirdTestServiceImpl {

        @Override
        @Decorator(Decorator3.class)
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static List<Class<?>> values(List<DecoratorAndOrder> list) {
        return list.stream()
                   .map(decorator -> {
                       final Decorator decoratorAnnotation = decorator.decoratorAnnotation();
                       if (decoratorAnnotation != null) {
                           assertThat(decorator.decoratorFactory()).isNull();
                           return decoratorAnnotation.value();
                       }
                       final DecoratorFactory decoratorFactory = decorator.decoratorFactory();
                       assertThat(decoratorFactory).isNotNull();
                       return decoratorFactory.value();
                   })
                   .collect(toImmutableList());
    }
}
