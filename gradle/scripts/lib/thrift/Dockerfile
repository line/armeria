# this builds a thrift binary without library support (only for code generation)
# because Apache thrift official repo no longer maintain a binary distribution
FROM ubuntu:focal as builder

ENV DEBIAN_FRONTEND=noninteractive

# see https://archive.apache.org/dist/thrift/
ENV THRIFT_VERSION=0.16.0

# see https://thrift.apache.org/docs/install/debian.html
RUN apt-get update -yq && \
  apt-get install -y --no-install-recommends \
  automake \
  bison \
  flex \
  g++ \
  git \
  libboost-all-dev \
  libevent-dev \
  libssl-dev \
  libtool \
  make \
  pkg-config

ADD https://archive.apache.org/dist/thrift/${THRIFT_VERSION}/thrift-${THRIFT_VERSION}.tar.gz /tmp/thrift.tar.gz

RUN echo "f460b5c1ca30d8918ff95ea3eb6291b3951cf518553566088f3f2be8981f6209 /tmp/thrift.tar.gz" | sha256sum -c && \
  tar xzf /tmp/thrift.tar.gz -C /tmp

RUN cd /tmp/thrift-${THRIFT_VERSION} && \
  ./bootstrap.sh && \
  ./configure \
  --disable-debug \
  --disable-tests \
  --without-erlang \
  --without-haskell \
  --without-java \
  --without-perl \
  --without-php \
  --without-php_extension \
  --without-python \
  --without-py3 \
  --without-ruby \
  --without-swift \
  && \
  make -j$(nproc) && \
  make install

FROM ubuntu:focal

COPY --from=builder /usr/local/bin/thrift /usr/local/bin/thrift

ENTRYPOINT [ "/usr/local/bin/thrift" ]

CMD [ "-help" ]
