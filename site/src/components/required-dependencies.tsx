import React from 'react';
import { Tabs as AntdTabs } from 'antd';
import CodeBlock from './code-block';
import versionsJson from '../../gen-src/versions.json';

const versions: any = versionsJson;

interface Dependency {
  groupId: string;
  artifactId: string;
}

interface RequiredDependenciesProps {
  boms?: Dependency[];
  dependencies: Dependency[];
}

function groovyBom(boms: Dependency[]) {
  return `${boms
    .map((bom) => {
      const key = `${bom.groupId}:${bom.artifactId}`;
      return `    implementation platform('${bom.groupId}:${bom.artifactId}:${versions[key]}')`;
    })
    .join('\n')}\n\n`;
}

function groovyDependency(props: RequiredDependenciesProps) {
  const statements: string = props.dependencies
    .map(
      (dependency) =>
        `    implementation '${dependency.groupId}:${dependency.artifactId}'`,
    )
    .join('\n');
  return `
dependencies {
${props.boms == null ? '' : groovyBom(props.boms)}    ...
${statements}
}
`;
}

function mavenBom(boms: Dependency[]) {
  return `<dependencyManagement>
  <dependencies>
${boms
  .map((bom) => {
    const key = `${bom.groupId}:${bom.artifactId}`;
    return `    <dependency>
      <groupId>com.linecorp.armeria</groupId>
      <artifactId>armeria-bom</artifactId>
      <version>${versions[key]}</version>
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
          {groovyDependency(props)}
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
