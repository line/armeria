### Benchmarks gRPC server with [ghz](https://github.com/bojand/ghz)

#### Install [ghz](https://github.com/bojand/ghz)

Use Homebrew for macOS. 
```
brew install ghz
```
Otherwise, please refer to [ghz#install](https://github.com/bojand/ghz#install).

#### Benchmark gRPC server
```bash
# Automacially start and load a gRPC server.
./bench.sh

# Load the running gRPC server that is listening on 50051 port.
GRPC_SERVER_START=false ./bench.sh
```

See [bench.sh](./bench.sh) for more options.
