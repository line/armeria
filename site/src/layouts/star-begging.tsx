import React, { useEffect, useState } from 'react';

import * as styles from './star-begging.module.less';

// Forked from https://github.com/javalin/javalin.github.io/blob/36bc38763043bd9326b1b8c8d7acca84db3e5cc1/_includes/starBegging.html

const StarBegging: React.FC = () => {
  const [display, setDisplay] = useState(false);
  useEffect(() => {
    if (localStorage.getItem('dismissed') !== 'true') {
      setTimeout(() => setDisplay(true), 10000); // 10 seconds
    }
  });

  function close() {
    setDisplay(false);
    localStorage.setItem('dismissed', 'true');
  }

  function handleKeyDown(e) {
    if (e.keyCode === 13) {
      close();
    }
  }

  return (
    <div id={styles.starBegging} className={display ? styles.on : styles.off }>
      <p>
        Like Armeria?<br/>Star us ⭐️
      </p>
      <span
        role="button"
        tabIndex={0}
        className={styles.close}
        onClick={close}
        onKeyDown={handleKeyDown}
      >×</span>
      <iframe
        id="starFrame"
        className="githubStar"
        title="github-stars"
        src="https://ghbtns.com/github-btn.html?user=line&amp;repo=armeria&amp;type=star&amp;count=true&size=large"
        frameBorder="0"
        scrolling="0"
        width="150px"
        height="30px"
      />
    </div>
  );
};

export default StarBegging;
