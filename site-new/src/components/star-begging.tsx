import React, { useEffect, useState, useRef } from 'react';

import styles from './star-begging.module.css';

// Forked from https://github.com/javalin/javalin.github.io/blob/36bc38763043bd9326b1b8c8d7acca84db3e5cc1/_includes/starBegging.html

const StarBegging: React.FC = () => {
  const [display, setDisplay] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);

  useEffect(() => {
    if (localStorage.getItem('dismissed') !== 'true') {
      setTimeout(() => setDisplay(true), 10000); // 10 seconds
    }
  }, []);

  function close() {
    setDisplay(false);
    localStorage.setItem('dismissed', 'true');
  }

  function handleKeyDown(e) {
    if (e.keyCode === 13) {
      close();
    }
  }

  // Detect when the iframe is clicked and close the popup
  useEffect(() => {
    const handleBlur = () => {
      setTimeout(() => {
        if (document.activeElement === iframeRef.current) {
          close();
        }
      }, 0);
    };

    window.addEventListener('blur', handleBlur);

    return () => {
      window.removeEventListener('blur', handleBlur);
    };
  }, []);

  return (
    <div id={styles.starBegging} className={display ? styles.on : styles.off}>
      <p>
        Like Armeria?
        <br />
        Star us ⭐️
      </p>
      <span
        role="button"
        tabIndex={0}
        className={styles.close}
        onClick={close}
        onKeyDown={handleKeyDown}
      >
        ×
      </span>{' '}
      {/* eslint-disable-line prettier/prettier */}
      <iframe
        id="starFrame"
        ref={iframeRef}
        className="githubStar"
        title="github-stars"
        src="https://ghbtns.com/github-btn.html?user=line&repo=armeria&type=star&count=true&size=large"
        width="150px"
        height="30px"
      />
    </div>
  );
};

export default StarBegging;
