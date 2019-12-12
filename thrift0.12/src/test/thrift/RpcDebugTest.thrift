namespace java com.linecorp.armeria.common.thrift.text

struct RequestDetails {
  1: string detailsArg1,
  2: i64 detailsArg2
}

struct Response {
  1: string response
}

exception DebugException {
  1: string reason
}

service RpcDebugService {
  Response doDebug(1: string methodArg1, 2: i64 methodArg2, 3: RequestDetails details) throws (1: DebugException e),
}

service ChildRpcDebugService extends RpcDebugService {
}
