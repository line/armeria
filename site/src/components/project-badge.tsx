import React from 'react';

import * as styles from './project-badge.module.less';

interface ProjectBadgeProps {
  url: string;
}

const ProjectBadge: React.FC<ProjectBadgeProps> = (props) => (
  <span className={styles.badge}>
    <object data={props.url} role="img" aria-label="badge" />
  </span>
);

export default ProjectBadge;
