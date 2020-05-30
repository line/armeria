/*
 * Copyright 2019 LINE Corporation
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

export interface Version {
  artifactId: string;
  artifactVersion: string;
  commitTimeMillis: number;
  shortCommitHash: string;
  longCommitHash: string;
  repositoryStatus: string;
}

export function extractSimpleArtifactVersion(artifactVersion: string): string {
  const simpleArtifactVersion = artifactVersion.match(
    /^[0-9]+\.[0-9]+\.[0-9]+/,
  );
  return simpleArtifactVersion ? simpleArtifactVersion.toString() : '';
}

export function convertLongToUTCDate(date: number): string {
  return new Date(date).toUTCString();
}

interface ArtifactIdObject {
  artifactId: string;
}

function createMapByArtifactId<T extends ArtifactIdObject>(
  objs: T[],
): Map<string, T> {
  return new Map(objs.map((obj) => [obj.artifactId, obj] as [string, T]));
}

export class Versions {
  private readonly data: Version[];

  private versionsByArtifactId: Map<string, Version>;

  constructor(data: Version[]) {
    this.data = JSON.parse(JSON.stringify(data));
    this.versionsByArtifactId = createMapByArtifactId(this.data);
  }

  public getVersions(): Version[] {
    return this.data;
  }

  public getArmeriaArtifactVersion(): string {
    const armeriaVersion = this.getArmeriaVersion();
    if (armeriaVersion) {
      return armeriaVersion.artifactVersion;
    }
    return '';
  }

  public getArmeriaVersion(): Version | undefined {
    return this.versionsByArtifactId.get('armeria');
  }
}
