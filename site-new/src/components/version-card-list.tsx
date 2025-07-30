import React, { ReactNode } from 'react';
import { usePluginData } from '@docusaurus/useGlobalData';
import Link from '@docusaurus/Link';
import { compareVersions } from '@site/releaseNotesSidebarUtils';
import styles from './version-card-list.module.css';

interface VersionCardListProps {
  version: string;
}

interface PluginData {
  versions?: {
    docs: DocItem[];
  }[];
}

interface DocItem {
  id: string;
  path?: string;
}

function VersionCardItem({ id, path }: DocItem): ReactNode {
  return (
    <div className={`${styles.versionCardItem} col col--3`}>
      <Link to={path}>📄️ {id}</Link>
    </div>
  );
}

const VersionCardList: React.FC<VersionCardListProps> = ({ version }) => {
  const pluginData: PluginData = usePluginData(
    'docusaurus-plugin-content-docs',
    'release-notes',
  );
  const versionItems = pluginData.versions?.[0]?.docs
    ?.filter((doc) => doc.id?.startsWith(`${version}`))
    .sort((a, b) => compareVersions(a.id, b.id));

  return (
    <div className={`row`}>
      {versionItems.map((doc) => (
        <VersionCardItem key={doc.id} id={doc.id} path={doc.path} />
      ))}
    </div>
  );
};

export default VersionCardList;
