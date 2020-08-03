package example.armeria.grpc.kotlin;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.31.0)",
    comments = "Source: hello.proto")
public final class HelloServiceGrpc {

  private HelloServiceGrpc() {}

  public static final String SERVICE_NAME = "example.grpc.hello.HelloService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getHelloMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Hello",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getHelloMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getHelloMethod;
    if ((getHelloMethod = HelloServiceGrpc.getHelloMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getHelloMethod = HelloServiceGrpc.getHelloMethod) == null) {
          HelloServiceGrpc.getHelloMethod = getHelloMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Hello"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("Hello"))
              .build();
        }
      }
    }
    return getHelloMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getLazyHelloMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "LazyHello",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getLazyHelloMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getLazyHelloMethod;
    if ((getLazyHelloMethod = HelloServiceGrpc.getLazyHelloMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getLazyHelloMethod = HelloServiceGrpc.getLazyHelloMethod) == null) {
          HelloServiceGrpc.getLazyHelloMethod = getLazyHelloMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "LazyHello"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("LazyHello"))
              .build();
        }
      }
    }
    return getLazyHelloMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getBlockingHelloMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BlockingHello",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getBlockingHelloMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getBlockingHelloMethod;
    if ((getBlockingHelloMethod = HelloServiceGrpc.getBlockingHelloMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getBlockingHelloMethod = HelloServiceGrpc.getBlockingHelloMethod) == null) {
          HelloServiceGrpc.getBlockingHelloMethod = getBlockingHelloMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BlockingHello"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("BlockingHello"))
              .build();
        }
      }
    }
    return getBlockingHelloMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getShortBlockingHelloMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ShortBlockingHello",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getShortBlockingHelloMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getShortBlockingHelloMethod;
    if ((getShortBlockingHelloMethod = HelloServiceGrpc.getShortBlockingHelloMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getShortBlockingHelloMethod = HelloServiceGrpc.getShortBlockingHelloMethod) == null) {
          HelloServiceGrpc.getShortBlockingHelloMethod = getShortBlockingHelloMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ShortBlockingHello"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("ShortBlockingHello"))
              .build();
        }
      }
    }
    return getShortBlockingHelloMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getLotsOfRepliesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "LotsOfReplies",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getLotsOfRepliesMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getLotsOfRepliesMethod;
    if ((getLotsOfRepliesMethod = HelloServiceGrpc.getLotsOfRepliesMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getLotsOfRepliesMethod = HelloServiceGrpc.getLotsOfRepliesMethod) == null) {
          HelloServiceGrpc.getLotsOfRepliesMethod = getLotsOfRepliesMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "LotsOfReplies"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("LotsOfReplies"))
              .build();
        }
      }
    }
    return getLotsOfRepliesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getBlockingLotsOfRepliesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BlockingLotsOfReplies",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getBlockingLotsOfRepliesMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getBlockingLotsOfRepliesMethod;
    if ((getBlockingLotsOfRepliesMethod = HelloServiceGrpc.getBlockingLotsOfRepliesMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getBlockingLotsOfRepliesMethod = HelloServiceGrpc.getBlockingLotsOfRepliesMethod) == null) {
          HelloServiceGrpc.getBlockingLotsOfRepliesMethod = getBlockingLotsOfRepliesMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BlockingLotsOfReplies"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("BlockingLotsOfReplies"))
              .build();
        }
      }
    }
    return getBlockingLotsOfRepliesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getShortBlockingLotsOfRepliesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ShortBlockingLotsOfReplies",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getShortBlockingLotsOfRepliesMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getShortBlockingLotsOfRepliesMethod;
    if ((getShortBlockingLotsOfRepliesMethod = HelloServiceGrpc.getShortBlockingLotsOfRepliesMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getShortBlockingLotsOfRepliesMethod = HelloServiceGrpc.getShortBlockingLotsOfRepliesMethod) == null) {
          HelloServiceGrpc.getShortBlockingLotsOfRepliesMethod = getShortBlockingLotsOfRepliesMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ShortBlockingLotsOfReplies"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("ShortBlockingLotsOfReplies"))
              .build();
        }
      }
    }
    return getShortBlockingLotsOfRepliesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getLotsOfGreetingsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "LotsOfGreetings",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getLotsOfGreetingsMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getLotsOfGreetingsMethod;
    if ((getLotsOfGreetingsMethod = HelloServiceGrpc.getLotsOfGreetingsMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getLotsOfGreetingsMethod = HelloServiceGrpc.getLotsOfGreetingsMethod) == null) {
          HelloServiceGrpc.getLotsOfGreetingsMethod = getLotsOfGreetingsMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "LotsOfGreetings"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("LotsOfGreetings"))
              .build();
        }
      }
    }
    return getLotsOfGreetingsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getBidiHelloMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BidiHello",
      requestType = example.armeria.grpc.kotlin.Hello.HelloRequest.class,
      responseType = example.armeria.grpc.kotlin.Hello.HelloReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest,
      example.armeria.grpc.kotlin.Hello.HelloReply> getBidiHelloMethod() {
    io.grpc.MethodDescriptor<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply> getBidiHelloMethod;
    if ((getBidiHelloMethod = HelloServiceGrpc.getBidiHelloMethod) == null) {
      synchronized (HelloServiceGrpc.class) {
        if ((getBidiHelloMethod = HelloServiceGrpc.getBidiHelloMethod) == null) {
          HelloServiceGrpc.getBidiHelloMethod = getBidiHelloMethod =
              io.grpc.MethodDescriptor.<example.armeria.grpc.kotlin.Hello.HelloRequest, example.armeria.grpc.kotlin.Hello.HelloReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BidiHello"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  example.armeria.grpc.kotlin.Hello.HelloReply.getDefaultInstance()))
              .setSchemaDescriptor(new HelloServiceMethodDescriptorSupplier("BidiHello"))
              .build();
        }
      }
    }
    return getBidiHelloMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static HelloServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HelloServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HelloServiceStub>() {
        @java.lang.Override
        public HelloServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HelloServiceStub(channel, callOptions);
        }
      };
    return HelloServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static HelloServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HelloServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HelloServiceBlockingStub>() {
        @java.lang.Override
        public HelloServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HelloServiceBlockingStub(channel, callOptions);
        }
      };
    return HelloServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static HelloServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HelloServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HelloServiceFutureStub>() {
        @java.lang.Override
        public HelloServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HelloServiceFutureStub(channel, callOptions);
        }
      };
    return HelloServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class HelloServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void hello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(getHelloMethod(), responseObserver);
    }

    /**
     */
    public void lazyHello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(getLazyHelloMethod(), responseObserver);
    }

    /**
     */
    public void blockingHello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(getBlockingHelloMethod(), responseObserver);
    }

    /**
     */
    public void shortBlockingHello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(getShortBlockingHelloMethod(), responseObserver);
    }

    /**
     */
    public void lotsOfReplies(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(getLotsOfRepliesMethod(), responseObserver);
    }

    /**
     */
    public void blockingLotsOfReplies(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(getBlockingLotsOfRepliesMethod(), responseObserver);
    }

    /**
     */
    public void shortBlockingLotsOfReplies(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(getShortBlockingLotsOfRepliesMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloRequest> lotsOfGreetings(
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      return asyncUnimplementedStreamingCall(getLotsOfGreetingsMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloRequest> bidiHello(
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      return asyncUnimplementedStreamingCall(getBidiHelloMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getHelloMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_HELLO)))
          .addMethod(
            getLazyHelloMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_LAZY_HELLO)))
          .addMethod(
            getBlockingHelloMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_BLOCKING_HELLO)))
          .addMethod(
            getShortBlockingHelloMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_SHORT_BLOCKING_HELLO)))
          .addMethod(
            getLotsOfRepliesMethod(),
            asyncServerStreamingCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_LOTS_OF_REPLIES)))
          .addMethod(
            getBlockingLotsOfRepliesMethod(),
            asyncServerStreamingCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_BLOCKING_LOTS_OF_REPLIES)))
          .addMethod(
            getShortBlockingLotsOfRepliesMethod(),
            asyncServerStreamingCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_SHORT_BLOCKING_LOTS_OF_REPLIES)))
          .addMethod(
            getLotsOfGreetingsMethod(),
            asyncClientStreamingCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_LOTS_OF_GREETINGS)))
          .addMethod(
            getBidiHelloMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                example.armeria.grpc.kotlin.Hello.HelloRequest,
                example.armeria.grpc.kotlin.Hello.HelloReply>(
                  this, METHODID_BIDI_HELLO)))
          .build();
    }
  }

  /**
   */
  public static final class HelloServiceStub extends io.grpc.stub.AbstractAsyncStub<HelloServiceStub> {
    private HelloServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HelloServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HelloServiceStub(channel, callOptions);
    }

    /**
     */
    public void hello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getHelloMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void lazyHello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getLazyHelloMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void blockingHello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getBlockingHelloMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void shortBlockingHello(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getShortBlockingHelloMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void lotsOfReplies(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncServerStreamingCall(
          getChannel().newCall(getLotsOfRepliesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void blockingLotsOfReplies(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncServerStreamingCall(
          getChannel().newCall(getBlockingLotsOfRepliesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void shortBlockingLotsOfReplies(example.armeria.grpc.kotlin.Hello.HelloRequest request,
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      asyncServerStreamingCall(
          getChannel().newCall(getShortBlockingLotsOfRepliesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloRequest> lotsOfGreetings(
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      return asyncClientStreamingCall(
          getChannel().newCall(getLotsOfGreetingsMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloRequest> bidiHello(
        io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getBidiHelloMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   */
  public static final class HelloServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<HelloServiceBlockingStub> {
    private HelloServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HelloServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HelloServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public example.armeria.grpc.kotlin.Hello.HelloReply hello(example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return blockingUnaryCall(
          getChannel(), getHelloMethod(), getCallOptions(), request);
    }

    /**
     */
    public example.armeria.grpc.kotlin.Hello.HelloReply lazyHello(example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return blockingUnaryCall(
          getChannel(), getLazyHelloMethod(), getCallOptions(), request);
    }

    /**
     */
    public example.armeria.grpc.kotlin.Hello.HelloReply blockingHello(example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return blockingUnaryCall(
          getChannel(), getBlockingHelloMethod(), getCallOptions(), request);
    }

    /**
     */
    public example.armeria.grpc.kotlin.Hello.HelloReply shortBlockingHello(example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return blockingUnaryCall(
          getChannel(), getShortBlockingHelloMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<example.armeria.grpc.kotlin.Hello.HelloReply> lotsOfReplies(
        example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return blockingServerStreamingCall(
          getChannel(), getLotsOfRepliesMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<example.armeria.grpc.kotlin.Hello.HelloReply> blockingLotsOfReplies(
        example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return blockingServerStreamingCall(
          getChannel(), getBlockingLotsOfRepliesMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<example.armeria.grpc.kotlin.Hello.HelloReply> shortBlockingLotsOfReplies(
        example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return blockingServerStreamingCall(
          getChannel(), getShortBlockingLotsOfRepliesMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class HelloServiceFutureStub extends io.grpc.stub.AbstractFutureStub<HelloServiceFutureStub> {
    private HelloServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HelloServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HelloServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<example.armeria.grpc.kotlin.Hello.HelloReply> hello(
        example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getHelloMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<example.armeria.grpc.kotlin.Hello.HelloReply> lazyHello(
        example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getLazyHelloMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<example.armeria.grpc.kotlin.Hello.HelloReply> blockingHello(
        example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getBlockingHelloMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<example.armeria.grpc.kotlin.Hello.HelloReply> shortBlockingHello(
        example.armeria.grpc.kotlin.Hello.HelloRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getShortBlockingHelloMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_HELLO = 0;
  private static final int METHODID_LAZY_HELLO = 1;
  private static final int METHODID_BLOCKING_HELLO = 2;
  private static final int METHODID_SHORT_BLOCKING_HELLO = 3;
  private static final int METHODID_LOTS_OF_REPLIES = 4;
  private static final int METHODID_BLOCKING_LOTS_OF_REPLIES = 5;
  private static final int METHODID_SHORT_BLOCKING_LOTS_OF_REPLIES = 6;
  private static final int METHODID_LOTS_OF_GREETINGS = 7;
  private static final int METHODID_BIDI_HELLO = 8;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final HelloServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(HelloServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_HELLO:
          serviceImpl.hello((example.armeria.grpc.kotlin.Hello.HelloRequest) request,
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
          break;
        case METHODID_LAZY_HELLO:
          serviceImpl.lazyHello((example.armeria.grpc.kotlin.Hello.HelloRequest) request,
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
          break;
        case METHODID_BLOCKING_HELLO:
          serviceImpl.blockingHello((example.armeria.grpc.kotlin.Hello.HelloRequest) request,
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
          break;
        case METHODID_SHORT_BLOCKING_HELLO:
          serviceImpl.shortBlockingHello((example.armeria.grpc.kotlin.Hello.HelloRequest) request,
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
          break;
        case METHODID_LOTS_OF_REPLIES:
          serviceImpl.lotsOfReplies((example.armeria.grpc.kotlin.Hello.HelloRequest) request,
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
          break;
        case METHODID_BLOCKING_LOTS_OF_REPLIES:
          serviceImpl.blockingLotsOfReplies((example.armeria.grpc.kotlin.Hello.HelloRequest) request,
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
          break;
        case METHODID_SHORT_BLOCKING_LOTS_OF_REPLIES:
          serviceImpl.shortBlockingLotsOfReplies((example.armeria.grpc.kotlin.Hello.HelloRequest) request,
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_LOTS_OF_GREETINGS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.lotsOfGreetings(
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
        case METHODID_BIDI_HELLO:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.bidiHello(
              (io.grpc.stub.StreamObserver<example.armeria.grpc.kotlin.Hello.HelloReply>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class HelloServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    HelloServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return example.armeria.grpc.kotlin.Hello.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("HelloService");
    }
  }

  private static final class HelloServiceFileDescriptorSupplier
      extends HelloServiceBaseDescriptorSupplier {
    HelloServiceFileDescriptorSupplier() {}
  }

  private static final class HelloServiceMethodDescriptorSupplier
      extends HelloServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    HelloServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (HelloServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new HelloServiceFileDescriptorSupplier())
              .addMethod(getHelloMethod())
              .addMethod(getLazyHelloMethod())
              .addMethod(getBlockingHelloMethod())
              .addMethod(getShortBlockingHelloMethod())
              .addMethod(getLotsOfRepliesMethod())
              .addMethod(getBlockingLotsOfRepliesMethod())
              .addMethod(getShortBlockingLotsOfRepliesMethod())
              .addMethod(getLotsOfGreetingsMethod())
              .addMethod(getBidiHelloMethod())
              .build();
        }
      }
    }
    return result;
  }
}
