import React from 'react';
import CodeBlock from '@theme/CodeBlock';
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import versionsJson from '@site/gen-src-temp/versions.json';

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
    .join('\n')}\n`;
}

function gradleDependency(props: RequiredDependenciesProps) {
  const statements: string = props.dependencies
    .map(
      (dependency) =>
        `    implementation '${dependency.groupId}:${dependency.artifactId}'`,
    )
    .join('\n');
  return `dependencies {
${props.boms == null ? '' : gradleBom(props.boms)}    ...
${statements}
}`;
}

function gradleKotlinBom(boms: Dependency[]) {
  return `${boms
    .map((bom) => {
      const key = `${bom.groupId}:${bom.artifactId}`;
      let version;
      if (bom.version != null) {
        version = bom.version;
      } else {
        version = versions[key];
      }
      return `    implementation(platform("${key}:${version}"))`;
    })
    .join('\n')}\n`;
}

function gradleKotlinDependency(props: RequiredDependenciesProps) {
  const statements: string = props.dependencies
    .map(
      (dependency) =>
        `    implementation("${dependency.groupId}:${dependency.artifactId}")`,
    )
    .join('\n');
  return `dependencies {
${props.boms == null ? '' : gradleKotlinBom(props.boms)}    ...
${statements}
}`;
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
      return `  <dependency>
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

  return `${props.boms == null ? '' : mavenBom(props.boms)}<dependencies>
  ...
${statements}
</dependencies>
  `;
}

const RequiredDependencies: React.FC<RequiredDependenciesProps> = (props) => {
  return (
    <Tabs groupId="dependencies">
      <TabItem label="Gradle" value="gradle">
        <CodeBlock language="groovy" title="build.gradle">
          {gradleDependency(props)}
        </CodeBlock>
      </TabItem>
      <TabItem label="Gradle (Kotlin)" value="gradle_kotlin">
        <CodeBlock language="kotlin" title="build.gradle.kts">
          {gradleKotlinDependency(props)}
        </CodeBlock>
      </TabItem>
      <TabItem label="Maven" value="maven">
        <CodeBlock language="xml" title="pom.xml">
          {mavenDependency(props)}
        </CodeBlock>
      </TabItem>
    </Tabs>
  );
};

export default RequiredDependencies;
