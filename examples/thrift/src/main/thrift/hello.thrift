namespace java example.armeria.thrift

struct HelloRequest {
  1: required string name;
}

struct HelloReply {
  1: required string message;
}

service HelloService {
    HelloReply hello(1:HelloRequest request)
    HelloReply lazyHello (1:HelloRequest request)
    HelloReply blockingHello (1:HelloRequest request)
}
