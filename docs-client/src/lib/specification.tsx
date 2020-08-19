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

import React from 'react';
import { Link } from 'react-router-dom';

type DocString = string | JSX.Element;

interface HasDocString {
  docString?: DocString;
}

export interface Parameter {
  name: string;
  location?: string;
  childFieldInfos: Parameter[];
  requirement: string;
  typeSignature: string;
  docString?: DocString;
}

export interface Endpoint {
  hostnamePattern: string;
  pathMapping: string;
  defaultMimeType: string;
  availableMimeTypes: string[];
  regexPathPrefix?: string;
  fragment?: string;
}

export interface Method {
  name: string;
  returnTypeSignature: string;
  parameters: Parameter[];
  exceptionTypeSignatures: string[];
  endpoints: Endpoint[];
  exampleHeaders: { [name: string]: string }[];
  exampleRequests: string[];
  examplePaths: string[];
  exampleQueries: string[];
  httpMethod: string;
  docString?: DocString;
}

export interface Service {
  name: string;
  methods: Method[];
  exampleHeaders: { [name: string]: string }[];
  docString?: DocString;
}

export interface Value {
  name: string;
  intValue?: number;
  docString?: DocString;
}

export interface Enum {
  name: string;
  values: Value[];
  docString?: DocString;
}

export interface Field {
  name: string;
  requirement: string;
  typeSignature: string;
  docString?: DocString;
}

export interface Struct {
  name: string;
  fields: Field[];
  docString?: DocString;
}

export interface SpecificationData {
  services: Service[];
  enums: Enum[];
  structs: Struct[];
  exceptions: Struct[];
  exampleHeaders: { [name: string]: string }[];
}

export function simpleName(fullName: string): string {
  const lastDotIdx = fullName.lastIndexOf('.');
  return lastDotIdx >= 0 ? fullName.substring(lastDotIdx + 1) : fullName;
}

export function packageName(fullName: string): string {
  const lastDotIdx = fullName.lastIndexOf('.');
  return lastDotIdx >= 0 ? fullName.substring(0, lastDotIdx) : fullName;
}

interface NamedObject {
  name: string;
}

function createMapByName<T extends NamedObject>(objs: T[]): Map<string, T> {
  return new Map(objs.map((obj) => [obj.name, obj] as [string, T]));
}

export class Specification {
  private data: SpecificationData;

  private enumsByName: Map<string, Enum>;

  private servicesByName: Map<string, Service>;

  private structsByName: Map<string, Struct>;

  constructor(data: SpecificationData) {
    this.data = JSON.parse(JSON.stringify(data));

    this.enumsByName = createMapByName(this.data.enums);
    this.servicesByName = createMapByName(this.data.services);
    this.structsByName = createMapByName([
      ...this.data.structs,
      ...this.data.exceptions,
    ]);

    this.updateDocStrings();
  }

  public getServices(): Service[] {
    return this.data.services;
  }

  public getEnums(): Enum[] {
    return this.data.enums;
  }

  public getExceptions(): Struct[] {
    return this.data.exceptions;
  }

  public getStructs(): Struct[] {
    return this.data.structs;
  }

  public getExampleHeaders(): { [name: string]: string }[] {
    return this.data.exampleHeaders;
  }

  public getEnumByName(name: string): Enum | undefined {
    return this.enumsByName.get(name);
  }

  public getServiceByName(name: string): Service | undefined {
    return this.servicesByName.get(name);
  }

  public getStructByName(name: string): Struct | undefined {
    return this.structsByName.get(name);
  }

  public getTypeSignatureHtml(typeSignature: string) {
    // Split on all non-identifier parts and optimistically find matches for type identifiers.
    const parts = typeSignature.split(/([^\w.]+)/g);
    return <>{parts.map((part) => this.renderTypePart(part))}</>;
  }

  private renderTypePart(part: string) {
    let type: Enum | Struct | undefined = this.enumsByName.get(part);
    if (type) {
      return this.getTypeLink(type.name, 'enum');
    }
    type = this.structsByName.get(part);
    if (type) {
      return this.getTypeLink(type.name, 'struct');
    }
    return simpleName(part);
  }

  private getTypeLink(name: string, type: 'enum' | 'struct') {
    return (
      <Link key={`/${type}s/${name}`} to={`/${type}s/${name}`}>
        {simpleName(name)}
      </Link>
    );
  }

  private updateDocStrings() {
    for (const service of this.data.services) {
      for (const method of service.methods) {
        const childDocStrings = this.parseParamDocStrings(
          method.docString as string,
        );
        method.docString = this.removeParamDocStrings(
          method.docString as string,
        );
        for (const param of method.parameters) {
          const childDocString = childDocStrings.get(param.name);
          if (childDocString) {
            param.docString = childDocString;
          }
        }
      }
    }

    // TODO(trustin): Handle the docstrings of enum values.
    for (const enm of this.data.enums) {
      enm.docString = this.removeParamDocStrings(enm.docString as string);
    }

    this.updateStructDocStrings(this.data.structs);
    this.updateStructDocStrings(this.data.exceptions);

    this.data.enums.forEach(this.renderDocString);
    this.data.exceptions.forEach(this.renderDocString);
    for (const service of this.data.services) {
      this.renderDocString(service);
      for (const method of service.methods) {
        this.renderDocString(method as HasDocString);
        method.parameters.forEach(this.renderDocString);
      }
    }
    this.data.structs.forEach(this.renderDocString);
  }

  private updateStructDocStrings(structs: Struct[]) {
    // TODO(trustin): Handle the docstrings of return values and exceptions.
    for (const struct of structs) {
      const childDocStrings = this.parseParamDocStrings(
        struct.docString as string,
      );
      struct.docString = this.removeParamDocStrings(struct.docString as string);
      for (const field of struct.fields) {
        const childDocString = childDocStrings.get(field.name);
        if (childDocString) {
          field.docString = childDocString;
        }
      }
    }
  }

  private parseParamDocStrings(docString: string | undefined) {
    const parameters = new Map<string, string>();
    if (!docString) {
      return parameters;
    }
    const pattern = /@param\s+(\w+)[\s.]+(({@|[^@])*)(?=(@[\w]+|$|\s))/gm;
    let match = pattern.exec(docString);
    while (match != null) {
      parameters.set(match[1], match[2]);
      match = pattern.exec(docString);
    }
    return parameters;
  }

  private removeParamDocStrings(docString: string | undefined) {
    if (!docString) {
      return '';
    }
    return docString.replace(/@param .*[\n\r]*/gim, '');
  }

  private renderDocString(item: HasDocString) {
    if (!item.docString) {
      return;
    }
    const docString = item.docString as string;
    const lines = docString.split(/(?:\r\n|\n|\r)/gim);
    // eslint-disable-next-line no-param-reassign
    item.docString = (
      <>
        {lines.map((line, i) => (
          // eslint-disable-next-line react/no-array-index-key
          <React.Fragment key={`${line}-${i}`}>
            {line}
            {i < lines.length - 1 ? <br /> : null}
          </React.Fragment>
        ))}
      </>
    );
  }
}
