namespace java testing.resilience4j

struct HelloRequest {
  1: required string name;
}

struct HelloReply {
  1: required string message;
}

exception NoHelloException {
}

service TestService {
    HelloReply hello(1:HelloRequest request) throws (1:NoHelloException nhe)
}
