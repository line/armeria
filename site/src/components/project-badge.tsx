import React from 'react';

import styles from './project-badge.module.less';

interface ProjectBadgeProps {
  url: string;
}

const ProjectBadge: React.FC<ProjectBadgeProps> = props => (
  <span className={styles.badge}>
    <object data={props.url} role="img" aria-label="" />
  </span>
);

export default ProjectBadge;
