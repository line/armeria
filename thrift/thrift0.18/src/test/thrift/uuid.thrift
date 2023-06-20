namespace java com.linecorp.armeria.common.thrift.text.uuid

struct UuidMessage {
  1: uuid id
  2: string message
}

service UuidService {
  UuidMessage echo(1: UuidMessage request)
}
