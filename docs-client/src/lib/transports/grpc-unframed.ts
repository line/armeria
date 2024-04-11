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

import Transport from './transport';
import { validateJsonObject } from '../json-util';

export const GRPC_UNFRAMED_MIME_TYPE =
  'application/json; charset=utf-8; protocol=gRPC';

export default class GrpcUnframedTransport extends Transport {
  public supportsMimeType(mimeType: string): boolean {
    return mimeType === GRPC_UNFRAMED_MIME_TYPE;
  }

  public getDebugMimeType(): string {
    return GRPC_UNFRAMED_MIME_TYPE;
  }

  protected async doSend(
    method: Method,
    headers: { [name: string]: string },
    pathPrefix: string,
    bodyJson?: string,
    endpointPath?: string,
  ): Promise<Response> {
    if (!bodyJson) {
      throw new Error('A gRPC request must have body.');
    }
    const endpoint = this.getDebugMimeTypeEndpoint(method, endpointPath);

    const hdrs = new Headers();
    // protocol=gRPC is added to mime types for the purpose of endpoint detection
    // but is not actually used by unframed gRPC services. We use a simpler mime
    // type when actually sending the request to better represent what a real client
    // would do.
    hdrs.set('content-type', 'application/json; charset=utf-8');
    for (const [name, value] of Object.entries(headers)) {
      hdrs.set(name, value);
    }
    if (bodyJson && bodyJson.trim()) {
      validateJsonObject(bodyJson, 'request body');
    }

    const newPath = pathPrefix + (endpointPath ?? endpoint.pathMapping);

    return fetch(newPath, {
      headers: hdrs,
      method: 'POST',
      body: bodyJson,
    });
  }
}
