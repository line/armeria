/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

const http = require('node:http');

const { buildSchema, graphqlSync } = require('graphql');

const port = Number(process.argv[2]);
if (!Number.isInteger(port)) {
  throw new Error('The mock server port is required.');
}

const annotatedHttpMimeType = 'application/json; charset=utf-8';
const graphqlMimeType = 'application/graphql+json';
const grpcMimeType = 'application/json; charset=utf-8; protocol=gRPC';
const thriftMimeType = 'application/x-thrift; protocol=TTEXT';

const none = (docString) => ({ docString, markup: 'NONE' });
const markdown = (docString) => ({ docString, markup: 'MARKDOWN' });
const mermaid = (docString) => ({ docString, markup: 'MERMAID' });

function endpoint(pathMapping, defaultMimeType, options = {}) {
  return {
    hostnamePattern: options.hostnamePattern || '*',
    pathMapping,
    defaultMimeType,
    availableMimeTypes: options.availableMimeTypes || [defaultMimeType],
    ...(options.regexPathPrefix
      ? { regexPathPrefix: options.regexPathPrefix }
      : {}),
    ...(options.fragment ? { fragment: options.fragment } : {}),
  };
}

function method(serviceName, name, httpMethod, options = {}) {
  return {
    name,
    id: `${serviceName}/${name}/${httpMethod}`,
    returnTypeSignature: options.returnTypeSignature || 'example.Message',
    parameters: options.parameters || [],
    exceptionTypeSignatures: options.exceptionTypeSignatures || [],
    endpoints: options.endpoints || [],
    exampleHeaders: options.exampleHeaders || [],
    exampleRequests: options.exampleRequests || [],
    examplePaths: options.examplePaths || [],
    exampleQueries: options.exampleQueries || [],
    httpMethod,
  };
}

const httpServiceName = 'example.HttpService';
const grpcServiceName = 'example.GrpcService';
const thriftServiceName = 'example.ThriftService';
const graphqlServiceName = 'example.GraphqlService';
const staticServiceName = 'example.StaticService';

const httpMethods = [
  method(httpServiceName, 'hello', 'GET', {
    exceptionTypeSignatures: ['example.ServiceException'],
    endpoints: [
      endpoint('exact:/hello', annotatedHttpMimeType, {
        hostnamePattern: 'api.example.com',
        availableMimeTypes: [annotatedHttpMimeType, 'text/plain'],
      }),
    ],
  }),
  method(httpServiceName, 'echo', 'POST', {
    parameters: [
      {
        name: 'request',
        location: 'BODY',
        requirement: 'REQUIRED',
        typeSignature: 'example.Request',
      },
      {
        name: 'traceId',
        location: 'HEADER',
        requirement: 'OPTIONAL',
        typeSignature: 'string',
      },
    ],
    endpoints: [endpoint('prefix:/echo', annotatedHttpMimeType)],
    exampleHeaders: [{ 'x-example-header': 'method' }],
    exampleRequests: [
      '{"name":"example request","details":{"count":1}}',
      '{"name":"second request","details":{"count":2}}',
    ],
    examplePaths: ['/echo/example'],
    exampleQueries: ['page=1&sort=name'],
  }),
  method(httpServiceName, 'regex', 'GET', {
    endpoints: [endpoint('regex:^/items/[0-9]+$', annotatedHttpMimeType)],
    examplePaths: ['/items/42'],
  }),
  method(httpServiceName, 'regexWithPrefix', 'GET', {
    endpoints: [
      endpoint('regex:^/items/[0-9]+$', annotatedHttpMimeType, {
        regexPathPrefix: 'prefix:/api/',
      }),
    ],
    examplePaths: ['/api/items/42'],
  }),
  method(httpServiceName, 'multiExact', 'GET', {
    endpoints: [
      endpoint('exact:/multi/a', annotatedHttpMimeType),
      endpoint('exact:/multi/b', annotatedHttpMimeType),
    ],
    examplePaths: ['/multi/a', '/multi/b'],
  }),
  method(httpServiceName, 'empty', 'GET', {
    returnTypeSignature: 'void',
    endpoints: [endpoint('exact:/empty', annotatedHttpMimeType)],
  }),
  method(httpServiceName, 'failure', 'GET', {
    endpoints: [endpoint('exact:/failure', annotatedHttpMimeType)],
  }),
  method(httpServiceName, 'text', 'GET', {
    returnTypeSignature: 'string',
    endpoints: [endpoint('exact:/text', annotatedHttpMimeType)],
  }),
  method(httpServiceName, 'invalidJson', 'GET', {
    returnTypeSignature: 'string',
    endpoints: [endpoint('exact:/invalid-json', annotatedHttpMimeType)],
  }),
  method(httpServiceName, 'noContentType', 'GET', {
    returnTypeSignature: 'string',
    endpoints: [endpoint('exact:/no-content-type', annotatedHttpMimeType)],
  }),
  method(httpServiceName, 'options', 'OPTIONS', {
    endpoints: [endpoint('exact:/verbs/options', annotatedHttpMimeType)],
  }),
  method(httpServiceName, 'head', 'HEAD', {
    endpoints: [endpoint('exact:/verbs/head', annotatedHttpMimeType)],
  }),
  method(httpServiceName, 'put', 'PUT', {
    endpoints: [endpoint('exact:/verbs/put', annotatedHttpMimeType)],
    exampleRequests: ['{"verb":"PUT"}'],
  }),
  method(httpServiceName, 'patch', 'PATCH', {
    endpoints: [endpoint('exact:/verbs/patch', annotatedHttpMimeType)],
    exampleRequests: ['{"verb":"PATCH"}'],
  }),
  method(httpServiceName, 'delete', 'DELETE', {
    endpoints: [endpoint('exact:/verbs/delete', annotatedHttpMimeType)],
    exampleRequests: ['{"verb":"DELETE"}'],
  }),
  method(httpServiceName, 'trace', 'TRACE', {
    endpoints: [endpoint('exact:/verbs/trace', 'text/plain')],
  }),
];

const specification = {
  services: [
    {
      name: httpServiceName,
      methods: httpMethods,
      exampleHeaders: [{ 'x-example-header': 'service' }],
    },
    {
      name: grpcServiceName,
      methods: [
        method(grpcServiceName, 'greet', 'POST', {
          parameters: [
            {
              name: 'request',
              location: 'BODY',
              requirement: 'REQUIRED',
              typeSignature: 'example.GrpcRequest',
            },
          ],
          endpoints: [endpoint('/example.GrpcService/Greet', grpcMimeType)],
          exampleRequests: ['{"name":"Armeria","count":2}'],
        }),
        method(grpcServiceName, 'inline', 'POST', {
          parameters: [
            {
              name: 'inlineName',
              location: 'BODY',
              requirement: 'OPTIONAL',
              typeSignature: 'string',
            },
          ],
          endpoints: [endpoint('/example.GrpcService/Inline', grpcMimeType)],
        }),
        method(grpcServiceName, 'missingSchema', 'POST', {
          endpoints: [
            endpoint('/example.GrpcService/MissingSchema', grpcMimeType),
          ],
        }),
      ],
      exampleHeaders: [],
    },
    {
      name: thriftServiceName,
      methods: [
        method(thriftServiceName, 'greet', 'POST', {
          parameters: [
            {
              name: 'request',
              location: 'BODY',
              requirement: 'REQUIRED',
              typeSignature: 'example.ThriftRequest',
            },
          ],
          endpoints: [
            endpoint('/thrift', thriftMimeType, {
              fragment: 'ExampleService',
            }),
          ],
          exampleRequests: ['{"name":"Armeria"}'],
        }),
        method(thriftServiceName, 'plain', 'POST', {
          parameters: [
            {
              name: 'request',
              location: 'BODY',
              requirement: 'REQUIRED',
              typeSignature: 'example.ThriftRequest',
            },
          ],
          endpoints: [endpoint('/thrift-plain', thriftMimeType)],
          exampleRequests: ['{"name":"Plain"}'],
        }),
      ],
      exampleHeaders: [],
    },
    {
      name: graphqlServiceName,
      methods: [
        method(graphqlServiceName, 'execute', 'POST', {
          endpoints: [endpoint('exact:/graphql', graphqlMimeType)],
          exampleRequests: [
            '{"query":"query { greeting(name: \\"Armeria\\") { message } }","variables":{}}',
          ],
        }),
        method(graphqlServiceName, 'noSchema', 'POST', {
          endpoints: [endpoint('exact:/graphql-no-schema', graphqlMimeType)],
        }),
        method(graphqlServiceName, 'noSchemaData', 'POST', {
          endpoints: [endpoint('exact:/graphql-no-data', graphqlMimeType)],
        }),
        method(graphqlServiceName, 'alternate', 'POST', {
          endpoints: [endpoint('exact:/graphql-alternate', graphqlMimeType)],
        }),
        method(graphqlServiceName, 'invalidSchema', 'POST', {
          endpoints: [
            endpoint('exact:/graphql-invalid-schema', graphqlMimeType),
          ],
        }),
        method(graphqlServiceName, 'pendingSchema', 'POST', {
          endpoints: [endpoint('exact:/graphql-pending', graphqlMimeType)],
        }),
      ],
      exampleHeaders: [],
    },
    {
      name: staticServiceName,
      methods: [
        method(staticServiceName, 'download', 'GET', {
          returnTypeSignature: 'binary',
          endpoints: [endpoint('exact:/download', 'application/octet-stream')],
        }),
      ],
      exampleHeaders: [],
    },
  ],
  enums: [
    {
      name: 'example.State',
      descriptionInfo: markdown('Current **state** of the response.'),
      values: [
        {
          name: 'READY',
          intValue: 1,
          descriptionInfo: none('Ready to serve.'),
        },
        {
          name: 'BUSY',
          intValue: 0,
          descriptionInfo: none('Temporarily busy.'),
        },
        {
          name: 'UNKNOWN',
          descriptionInfo: none(''),
        },
        {
          name: 'UNSPECIFIED',
          descriptionInfo: markdown('\n  Indented description.'),
        },
      ],
    },
    {
      name: 'example.EmptyEnum',
      descriptionInfo: none('An enum without values.'),
      values: [],
    },
  ],
  structs: [
    {
      name: 'example.Message',
      alias: 'MessageAlias',
      descriptionInfo: none('A response message.'),
      fields: [
        {
          name: 'message',
          location: 'UNSPECIFIED',
          requirement: 'REQUIRED',
          typeSignature: 'string',
          descriptionInfo: none('Rendered message.'),
        },
        {
          name: 'id',
          location: 'UNSPECIFIED',
          requirement: 'OPTIONAL',
          typeSignature: 'long',
          descriptionInfo: none('Large identifier.'),
        },
        {
          name: 'state',
          location: 'UNSPECIFIED',
          requirement: 'UNSPECIFIED',
          typeSignature: 'example.State',
          descriptionInfo: none('Response state.'),
        },
      ],
    },
    {
      name: 'example.Request',
      descriptionInfo: markdown('A request with `nested` fields.'),
      fields: [
        {
          name: 'name',
          location: 'BODY',
          requirement: 'REQUIRED',
          typeSignature: 'string',
          descriptionInfo: none('Name to echo.'),
        },
        {
          name: 'details',
          location: 'BODY',
          requirement: 'OPTIONAL',
          typeSignature: 'example.Details',
          descriptionInfo: none('Additional details.'),
        },
      ],
    },
    {
      name: 'example.Details',
      descriptionInfo: none('Nested request details.'),
      fields: [
        {
          name: 'count',
          location: 'BODY',
          requirement: 'OPTIONAL',
          typeSignature: 'int',
          descriptionInfo: none('Repeat count.'),
        },
      ],
    },
    {
      name: 'example.GrpcRequest',
      descriptionInfo: none('gRPC request body.'),
      fields: [
        {
          name: 'name',
          location: 'BODY',
          requirement: 'REQUIRED',
          typeSignature: 'string',
          descriptionInfo: none('Person to greet.'),
        },
        {
          name: 'count',
          location: 'BODY',
          requirement: 'OPTIONAL',
          typeSignature: 'int',
          descriptionInfo: none('Greeting count.'),
        },
      ],
    },
    {
      name: 'example.ThriftRequest',
      descriptionInfo: none('Thrift request body.'),
      fields: [
        {
          name: 'name',
          location: 'BODY',
          requirement: 'REQUIRED',
          typeSignature: 'string',
          descriptionInfo: none('Person to greet.'),
        },
      ],
    },
    {
      name: 'example.EmptyStruct',
      descriptionInfo: none('A struct without fields.'),
      fields: [],
    },
  ],
  exceptions: [
    {
      name: 'example.ServiceException',
      descriptionInfo: none('An example service error.'),
      fields: [
        {
          name: 'code',
          location: 'UNSPECIFIED',
          requirement: 'REQUIRED',
          typeSignature: 'int',
          descriptionInfo: none('Error code.'),
        },
      ],
    },
  ],
  exampleHeaders: [{ 'x-example-header': 'global' }],
  docServiceRoute: {
    pathType: 'PREFIX',
    patternString: '/docs/*',
  },
  docServiceExtraInfo: {
    webAppTitle: 'E2E Docs',
  },
  docStrings: {
    [httpServiceName]: none('HTTP service description.'),
    [`${httpServiceName}/hello`]: markdown(`# Hello

This is **Markdown** with ~~old text~~, an [Armeria link](https://armeria.dev), and a task list.

- [x] rendered

| Name | Value |
| --- | --- |
| hello | world |

\`inline code\`

\`\`\`json
{"message":"hello"}
\`\`\``),
    [`${httpServiceName}/hello:return`]: none('The returned message.'),
    [`${httpServiceName}/hello:throws/example.ServiceException`]: none(
      'Thrown when the service cannot respond.',
    ),
    [`${httpServiceName}/echo`]: none(
      'Echoes the request.\n@param request removed from rendered text',
    ),
    [`${httpServiceName}/echo:param/request`]: markdown(
      'The **request** to echo.',
    ),
    [`${httpServiceName}/echo:param/traceId`]: none('Optional trace header.'),
    [grpcServiceName]: none('gRPC service description.'),
    [`${grpcServiceName}/greet`]: mermaid(
      'flowchart LR\n  Client[Client] --> Server[Server]',
    ),
    [thriftServiceName]: none('Thrift service description.'),
    [graphqlServiceName]: none('GraphQL service description.'),
    [staticServiceName]: none('A service without a debug transport.'),
  },
};

const emptySpecification = {
  services: [],
  enums: [],
  structs: [],
  exceptions: [],
  exampleHeaders: [],
  docServiceExtraInfo: {},
  docStrings: {},
};

const noRouteSpecification = {
  ...specification,
  docServiceRoute: undefined,
};

const exactRouteSpecification = {
  ...specification,
  docServiceRoute: {
    pathType: 'EXACT',
    patternString: '/docs',
  },
};

const duplicateSpecification = {
  services: ['alpha', 'beta'].map((prefix) => ({
    name: `${prefix}.DuplicateService`,
    methods: [
      method(`${prefix}.DuplicateService`, 'get', 'GET', {
        endpoints: [
          endpoint(`exact:/${prefix}/duplicate`, annotatedHttpMimeType),
        ],
      }),
    ],
    exampleHeaders: [],
  })),
  enums: ['alpha', 'beta'].map((prefix) => ({
    name: `${prefix}.DuplicateEnum`,
    descriptionInfo: none(`${prefix} enum`),
    values: [],
  })),
  structs: ['alpha', 'beta'].map((prefix) => ({
    name: `${prefix}.DuplicateStruct`,
    descriptionInfo: none(`${prefix} struct`),
    fields: [],
  })),
  exceptions: ['alpha', 'beta'].map((prefix) => ({
    name: `${prefix}.DuplicateException`,
    descriptionInfo: none(`${prefix} exception`),
    fields: [],
  })),
  exampleHeaders: [],
  docServiceExtraInfo: {},
  docStrings: {},
};

const jsonSchemas = {
  $defs: {
    methods: {
      grpcGreet: {
        $id: `${grpcServiceName}/greet/POST`,
        properties: {
          request: { $ref: '#/$defs/models/example.GrpcRequest' },
        },
      },
      thriftGreet: {
        $id: `${thriftServiceName}/greet/POST`,
        properties: {
          request: { $ref: '#/$defs/models/example.ThriftRequest' },
        },
      },
      grpcInline: {
        $id: `${grpcServiceName}/inline/POST`,
        type: 'object',
        properties: {
          inlineName: { type: 'string', description: 'Inline value' },
        },
      },
    },
    models: {
      'example.GrpcRequest': {
        type: 'object',
        additionalProperties: false,
        required: ['name'],
        properties: {
          name: { type: 'string', description: 'Person to greet' },
          count: { type: 'integer', description: 'Greeting count' },
        },
      },
      'example.ThriftRequest': {
        type: 'object',
        additionalProperties: false,
        required: ['name'],
        properties: {
          name: { type: 'string', description: 'Person to greet' },
        },
      },
    },
  },
};

const versions = [
  {
    artifactId: 'armeria',
    artifactVersion: '1.2.3-SNAPSHOT',
    commitTimeMillis: 1700000000000,
    shortCommitHash: 'abcdef0',
    longCommitHash: 'abcdef0123456789',
    repositoryStatus: 'clean',
  },
  {
    artifactId: 'armeria-grpc',
    artifactVersion: '1.2.4',
    commitTimeMillis: 0,
    shortCommitHash: '1234567',
    longCommitHash: '1234567890abcdef',
    repositoryStatus: 'dirty',
  },
];

const graphQLSchema = buildSchema(`
  type Greeting {
    message: String!
  }

  type Query {
    greeting(name: String!): Greeting!
  }
`);

const graphQLRoot = {
  greeting: ({ name }) => ({ message: `Hello, ${name}` }),
};

const alternateGraphQLSchema = buildSchema(`
  type Query {
    farewell: String!
  }
`);

const alternateGraphQLRoot = {
  farewell: () => 'Goodbye',
};

function sendRaw(response, body, status = 200, headers = {}) {
  response.writeHead(status, {
    'content-type': 'text/plain; charset=utf-8',
    'x-e2e-response': 'true',
    ...headers,
  });
  response.end(body);
}

function sendJson(response, body, status = 200, headers = {}) {
  sendRaw(response, JSON.stringify(body), status, {
    'content-type': 'application/json; charset=utf-8',
    ...headers,
  });
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => {
      body += chunk;
    });
    request.on('end', () => resolve(body));
    request.on('error', reject);
  });
}

function parseBody(body) {
  if (!body) {
    return null;
  }
  try {
    return JSON.parse(body);
  } catch (ignored) {
    return body;
  }
}

function normalizeApiPath(pathname) {
  return pathname.startsWith('/mounted/')
    ? pathname.substring('/mounted'.length)
    : pathname;
}

async function handleApi(request, response, requestUrl) {
  const pathname = normalizeApiPath(requestUrl.pathname);
  if (pathname === '/hello') {
    sendRaw(
      response,
      '{"message":"hello","id":9007199254740993,"state":"READY"}',
      200,
      { 'content-type': 'application/json; charset=utf-8' },
    );
    return true;
  }
  if (pathname === '/empty' || pathname === '/verbs/head') {
    sendRaw(response, '');
    return true;
  }
  if (pathname === '/failure') {
    sendJson(response, { message: 'teapot' }, 418);
    return true;
  }
  if (pathname === '/text') {
    sendRaw(response, 'plain response');
    return true;
  }
  if (pathname === '/invalid-json') {
    sendRaw(response, 'not valid json', 200, {
      'content-type': 'application/json; charset=utf-8',
    });
    return true;
  }
  if (pathname === '/no-content-type') {
    response.writeHead(200, { 'x-e2e-response': 'true' });
    response.end('response without content type');
    return true;
  }
  if (pathname === '/download') {
    sendRaw(response, 'download');
    return true;
  }
  if (pathname === '/graphql-no-schema') {
    await readBody(request);
    sendJson(response, 'schema unavailable');
    return true;
  }
  if (pathname === '/graphql-no-data') {
    await readBody(request);
    sendJson(response, {});
    return true;
  }
  if (pathname === '/graphql-invalid-schema') {
    await readBody(request);
    sendRaw(response, 'not json', 200, {
      'content-type': 'application/json; charset=utf-8',
    });
    return true;
  }
  if (pathname === '/graphql-pending') {
    await readBody(request);
    await new Promise((resolve) => {
      response.on('close', resolve);
    });
    return true;
  }
  if (pathname === '/graphql') {
    const body = parseBody(await readBody(request));
    if (!body || typeof body === 'string') {
      sendJson(
        response,
        { errors: [{ message: 'Invalid GraphQL body' }] },
        400,
      );
      return true;
    }
    const result = graphqlSync({
      schema: graphQLSchema,
      source: body.query,
      rootValue: graphQLRoot,
      variableValues: body.variables,
      operationName: body.operationName,
    });
    sendJson(response, result);
    return true;
  }
  if (pathname === '/graphql-alternate') {
    const body = parseBody(await readBody(request));
    if (!body || typeof body === 'string') {
      sendJson(
        response,
        { errors: [{ message: 'Invalid GraphQL body' }] },
        400,
      );
      return true;
    }
    const result = graphqlSync({
      schema: alternateGraphQLSchema,
      source: body.query,
      rootValue: alternateGraphQLRoot,
      variableValues: body.variables,
      operationName: body.operationName,
    });
    sendJson(response, result);
    return true;
  }

  const echoPaths = [
    '/echo',
    '/items/',
    '/api/items/',
    '/multi/',
    '/verbs/',
    '/example.GrpcService/Greet',
    '/example.GrpcService/Inline',
    '/example.GrpcService/MissingSchema',
    '/thrift',
    '/alpha/duplicate',
    '/beta/duplicate',
  ];
  if (echoPaths.some((prefix) => pathname.startsWith(prefix))) {
    const rawBody = await readBody(request);
    sendJson(response, {
      method: request.method,
      path: pathname,
      requestPath: requestUrl.pathname,
      query: Object.fromEntries(requestUrl.searchParams.entries()),
      headers: request.headers,
      body: parseBody(rawBody),
      rawBody,
    });
    return true;
  }
  return false;
}

const server = http.createServer(async (request, response) => {
  try {
    const requestUrl = new URL(request.url, `http://127.0.0.1:${port}`);
    const { pathname } = requestUrl;
    if (pathname.endsWith('/specification.json')) {
      if (pathname.includes('/spec-error/')) {
        sendRaw(response, 'not json');
      } else if (pathname.includes('/empty-docs/')) {
        sendJson(response, emptySpecification);
      } else if (pathname.includes('/duplicates/')) {
        sendJson(response, duplicateSpecification);
      } else if (pathname.includes('/no-route/')) {
        sendJson(response, noRouteSpecification);
      } else if (pathname.includes('/exact-route/')) {
        sendJson(response, exactRouteSpecification);
      } else {
        sendJson(response, specification);
      }
      return;
    }
    if (pathname.endsWith('/versions.json')) {
      sendJson(response, pathname.includes('/empty-docs/') ? [] : versions);
      return;
    }
    if (pathname.endsWith('/schemas.json')) {
      if (pathname.includes('/schema-error/')) {
        sendRaw(response, 'not json');
      } else {
        sendJson(response, jsonSchemas);
      }
      return;
    }
    if (pathname.endsWith('/injected.js')) {
      sendRaw(
        response,
        `window.__e2eInjected = true;
window.armeria.registerHeaderProvider(function() {
  return Promise.resolve({ 'x-e2e-injected': 'from-injected-script' });
});`,
        200,
        { 'content-type': 'application/javascript; charset=utf-8' },
      );
      return;
    }
    if (await handleApi(request, response, requestUrl)) {
      return;
    }
    response.writeHead(404);
    response.end();
  } catch (error) {
    sendJson(response, { error: error.message }, 500);
  }
});

server.listen(port, '127.0.0.1');
