import React from 'react';
import LazyLoad from 'react-lazyload';

import styles from './project-badge.module.less';

interface ProjectBadgeProps {
  url: string;
}

const ProjectBadge: React.FC<ProjectBadgeProps> = props => (
  <span className={styles.badge}>
    <LazyLoad height={20} once>
      <object data={props.url} role="img" aria-label="" />
    </LazyLoad>
  </span>
);

export default ProjectBadge;
