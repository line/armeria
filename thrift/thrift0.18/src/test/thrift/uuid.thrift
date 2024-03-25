namespace java testing.thrift.uuid

struct UuidMessage {
  1: uuid id
  2: string message
}

service TestUuidService {
  UuidMessage echo(1: UuidMessage request)
}
