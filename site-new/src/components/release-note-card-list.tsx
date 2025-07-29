import React, { ReactNode } from 'react';
import { usePluginData } from '@docusaurus/useGlobalData';
import Link from '@docusaurus/Link';
import { compareVersions } from '@site/releaseNotesSidebarUtils';
import styles from './release-note-card-list.module.css';

interface ReleaseNotesCardListProps {
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

function ReleaseNoteCardItem({ id, path }: DocItem): ReactNode {
  return (
    <div className={`${styles.releaseNoteCardItem} col col--3`}>
      <Link to={path}>📄️ {id}</Link>
    </div>
  );
}

const ReleaseNoteCardList: React.FC<ReleaseNotesCardListProps> = ({
  version,
}) => {
  const pluginData: PluginData = usePluginData(
    'docusaurus-plugin-content-docs',
    'release-notes',
  );
  const releaseNoteItems = pluginData.versions?.[0]?.docs
    ?.filter((doc) => doc.id?.startsWith(`${version}`))
    .sort((a, b) => compareVersions(a.id, b.id));

  return (
    <div className={`row`}>
      {releaseNoteItems.map((doc) => (
        <ReleaseNoteCardItem key={doc.id} id={doc.id} path={doc.path} />
      ))}
    </div>
  );
};

export default ReleaseNoteCardList;
