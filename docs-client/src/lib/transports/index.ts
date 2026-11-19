/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import { Method } from '../specification';

import AnnotatedHttpTransport from './annotated-http';
import GrpcUnframedTransport from './grpc-unframed';
import ThriftTransport from './thrift';
import { Transport } from './transport';
import GraphqlHttpTransport from './grahpql-http';

const debugTransports: Transport[] = [
  new GrpcUnframedTransport(),
  new ThriftTransport(),
  new AnnotatedHttpTransport(),
  new GraphqlHttpTransport(),
];

export class Transports {
  public getDebugTransport(method: Method): Transport | undefined {
    for (const mimeType of this.getEndpointMimeTypes(method)) {
      const transport = debugTransports.find((candidate) =>
        candidate.supportsMimeType(mimeType),
      );
      if (transport) {
        return transport;
      }
    }
    return undefined;
  }

  public getEndpointMimeTypes(method: Method): string[] {
    const mimeTypes = new Set<string>();
    for (const endpoint of method.endpoints) {
      endpoint.availableMimeTypes.forEach((mimeType) =>
        mimeTypes.add(mimeType),
      );
    }
    return [...mimeTypes];
  }

  public getSupportedMimeTypes(): string[] {
    return debugTransports.map((transport) => transport.getDebugMimeType());
  }
}

export const TRANSPORTS = new Transports();
