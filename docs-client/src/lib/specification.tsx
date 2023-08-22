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

export interface DescriptionInfo {
  docString: string;
  markup: string;
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
  id: string;
  returnTypeSignature: string;
  parameters: Field[];
  exceptionTypeSignatures: string[];
  endpoints: Endpoint[];
  exampleHeaders: { [name: string]: string }[];
  exampleRequests: string[];
  examplePaths: string[];
  exampleQueries: string[];
  httpMethod: string;
  descriptionInfo: DescriptionInfo;
}

export interface Service {
  name: string;
  methods: Method[];
  exampleHeaders: { [name: string]: string }[];
  descriptionInfo: DescriptionInfo;
}

export enum ServiceType {
  UNKNOWN = 'UNKNOWN',
  HTTP = 'HTTP',
  THRIFT = 'THRIFT',
  GRPC = 'GRPC',
  GRAPHQL = 'GRAPHQL',
}

export interface Value {
  name: string;
  intValue?: number;
  descriptionInfo: DescriptionInfo;
}

export interface Enum {
  name: string;
  values: Value[];
  descriptionInfo: DescriptionInfo;
}

export interface Field {
  name: string;
  location: string;
  requirement: string;
  typeSignature: string;
  descriptionInfo: DescriptionInfo;
}

export interface Struct {
  name: string;
  alias?: string;
  fields: Field[];
  descriptionInfo: DescriptionInfo;
}

export enum RoutePathType {
  EXACT = 'EXACT',
  PREFIX = 'PREFIX',
  PARAMETERIZED = 'PARAMETERIZED',
  REGEX = 'REGEX',
  REGEX_WITH_PREFIX = 'REGEX_WITH_PREFIX',
}

export interface Route {
  pathType: RoutePathType;
  patternString: string;
}

export interface SpecificationData {
  services: Service[];
  enums: Enum[];
  structs: Struct[];
  exceptions: Struct[];
  exampleHeaders: { [name: string]: string }[];
  docServiceRoute?: Route;
}

export function simpleName(fullName: string): string {
  const lastDotIdx = fullName.lastIndexOf('.');
  return lastDotIdx >= 0 ? fullName.substring(lastDotIdx + 1) : fullName;
}

export function packageName(fullName: string): string {
  const lastDotIdx = fullName.lastIndexOf('.');
  return lastDotIdx >= 0 ? fullName.substring(0, lastDotIdx) : fullName;
}

export function extractUrlPath(method: Method): string {
  const endpoints = method.endpoints;
  return endpoints[0].pathMapping.substring('exact:'.length);
}

interface NamedObject {
  name: string;
}

function createMapByName<T extends NamedObject>(objs: T[]): Map<string, T> {
  return new Map(objs.map((obj) => [obj.name, obj] as [string, T]));
}

function createMapByAlias(objs: Struct[]): Map<string, Struct> {
  return new Map(
    objs
      .filter((obj) => obj)
      .map((obj) => [obj.alias, obj] as [string, Struct]),
  );
}

function hasUniqueNames<T extends NamedObject>(map: Map<string, T>): boolean {
  const names = new Set();
  for (const key of map.keys()) {
    names.add(simpleName(key));
  }
  return names.size === map.size;
}

export class Specification {
  private data: SpecificationData;

  private readonly enumsByName: Map<string, Enum>;

  private readonly servicesByName: Map<string, Service>;

  private readonly structsByName: Map<string, Struct>;

  private readonly structsByAlias: Map<string, Struct>;

  private readonly uniqueEnumNames: boolean;

  private readonly uniqueServiceNames: boolean;

  private readonly uniqueStructNames: boolean;

  private readonly docServiceRoute?: Route;

  constructor(data: SpecificationData) {
    this.data = JSON.parse(JSON.stringify(data));

    this.enumsByName = createMapByName(this.data.enums);
    this.servicesByName = createMapByName(this.data.services);
    this.structsByName = createMapByName([
      ...this.data.structs,
      ...this.data.exceptions,
    ]);
    this.structsByAlias = createMapByAlias(this.data.structs);

    this.uniqueEnumNames = hasUniqueNames(this.enumsByName);
    this.uniqueServiceNames = hasUniqueNames(this.servicesByName);
    this.uniqueStructNames = hasUniqueNames(this.structsByName);

    this.updateDocStrings();
    this.docServiceRoute = this.data.docServiceRoute;
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

  public getDocServiceRoute(): Route | undefined {
    return this.docServiceRoute;
  }

  public hasUniqueEnumNames(): boolean {
    return this.uniqueEnumNames;
  }

  public hasUniqueServiceNames(): boolean {
    return this.uniqueServiceNames;
  }

  public hasUniqueStructNames(): boolean {
    return this.uniqueStructNames;
  }

  public getTypeSignatureHtml(typeSignature: string) {
    // Split on all non-identifier parts and optimistically find matches for type identifiers.
    const parts = typeSignature.split(/([^$\w.]+)/g);
    return <>{parts.map((part) => this.renderTypePart(part))}</>;
  }

  private renderTypePart(part: string) {
    let type: Enum | Struct | undefined = this.enumsByName.get(part);
    if (type) {
      return this.getTypeLink(type.name, 'enum');
    }
    type = this.structsByName.get(part) || this.structsByAlias.get(part);
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
          method.descriptionInfo.docString as string,
        );
        for (const param of method.parameters) {
          const childDocString = childDocStrings.get(param.name);
          if (childDocString) {
            param.descriptionInfo = {
              docString: childDocString,
              markup: 'NONE',
            };
          }
        }
      }
    }
    this.updateStructDocStrings(this.data.structs);
    this.updateStructDocStrings(this.data.exceptions);
  }

  private updateStructDocStrings(structs: Struct[]) {
    // TODO(trustin): Handle the docstrings of return values and exceptions.
    for (const struct of structs) {
      const childDocStrings = this.parseParamDocStrings(
        struct.descriptionInfo.docString as string,
      );
      for (const field of struct.fields) {
        const childDocString = childDocStrings.get(field.name);
        if (childDocString) {
          field.descriptionInfo = {
            docString: childDocString,
            markup: 'NONE',
          };
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
}
