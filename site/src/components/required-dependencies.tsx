import React from 'react';
import { Tabs as AntdTabs } from 'antd';
import CodeBlock from './code-block';
import versionsJson from '../../gen-src/versions.json';

const versions: any = versionsJson;

interface Dependency {
  groupId: string;
  artifactId: string;
  version?: string;
}

interface RequiredDependenciesProps {
  /* eslint-disable react/no-unused-prop-types */
  boms?: Dependency[];
  dependencies: Dependency[];
  /* eslint-enable react/no-unused-prop-types */
}

function gradleBom(boms: Dependency[]) {
  return `${boms
    .map((bom) => {
      const key = `${bom.groupId}:${bom.artifactId}`;
      let version;
      if (bom.version != null) {
        version = bom.version;
      } else {
        version = versions[key];
      }
      return `    implementation platform('${key}:${version}')`;
    })
    .join('\n')}\n\n`;
}

function gradleDependency(props: RequiredDependenciesProps) {
  const statements: string = props.dependencies
    .map(
      (dependency) =>
        `    implementation '${dependency.groupId}:${dependency.artifactId}'`,
    )
    .join('\n');
  return `
dependencies {
${props.boms == null ? '' : gradleBom(props.boms)}    ...
${statements}
}
`;
}

function mavenBom(boms: Dependency[]) {
  return `<dependencyManagement>
  <dependencies>
${boms
  .map((bom) => {
    let version;
    if (bom.version != null) {
      version = bom.version;
    } else {
      const key = `${bom.groupId}:${bom.artifactId}`;
      version = versions[key];
    }
    return `    <dependency>
      <groupId>${bom.groupId}</groupId>
      <artifactId>${bom.artifactId}</artifactId>
      <version>${version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>`;
  })
  .join('\n')}
  </dependencies>
</dependencyManagement>\n`;
}

function mavenDependency(props: RequiredDependenciesProps) {
  const statements: string = props.dependencies
    .map((dependency) => {
      return `  <dependency>
    <groupId>${dependency.groupId}</groupId>
    <artifactId>${dependency.artifactId}</artifactId>
  </dependency>`;
    })
    .join('\n');

  return `${props.boms == null ? '' : mavenBom(props.boms)}
<dependencies>
  ...
${statements}
</<dependencies>
`;
}

const RequiredDependencies: React.FC<RequiredDependenciesProps> = (props) => {
  return (
    <AntdTabs>
      <AntdTabs.TabPane tab="Gradle" key="gradle">
        <CodeBlock language="groovy" filename="build.gradle">
          {gradleDependency(props)}
        </CodeBlock>
      </AntdTabs.TabPane>
      <AntdTabs.TabPane tab="Maven" key="maven">
        <CodeBlock language="xml" filename="pom.xml">
          {mavenDependency(props)}
        </CodeBlock>
      </AntdTabs.TabPane>
    </AntdTabs>
  );
};

export default RequiredDependencies;
