namespace java testing.thrift.test

struct TestServiceRequest {
  1: string message,
}

struct TestServiceResponse {
  1: string response
}

service TestService {
  TestServiceResponse get(1: TestServiceRequest request),
}
