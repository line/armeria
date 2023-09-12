namespace java testing.spring.thrift

// Tests a non-oneway method with a return value.
service TestService {
    string hello(1:string name)
}
