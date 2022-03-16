import {
  CloseCircleOutlined,
  GithubOutlined,
  TwitterOutlined,
  MenuOutlined,
  SlackOutlined,
} from '@ant-design/icons';
import { RouteComponentProps } from '@reach/router';
import { Layout, Menu, Drawer, Button, Typography } from 'antd';
import Link from 'gatsby-link';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React, { useState, useCallback, useLayoutEffect } from 'react';
import StickyBox from 'react-sticky-box';

import Logo from '../components/logo';
import Mailchimp from '../components/mailchimp';

import * as styles from './header.module.less';

const { Header } = Layout;
const { Paragraph } = Typography;

const selectableKeysAndRegexes = {
  news: /\/(news|release-notes)(\/|$)/,
  docs: /\/(docs|tutorials)(\/|$)/,
  community: /\/community(\/|$)/,
  home: /.?/,
};

interface HeaderComponentProps extends RouteComponentProps {
  extraSidebarContent?: React.ReactNode;
}

const HeaderComponent: React.FC<HeaderComponentProps> = (props) => {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const selectedKeyAndRegex = Object.entries(selectableKeysAndRegexes).find(
    ([, regexp]) => props.location?.pathname?.match(regexp),
  );

  const selectedKeys = selectedKeyAndRegex ? [selectedKeyAndRegex[0]] : [];

  const [documentHeight, setDocumentHeight] = useState(0);
  const openSidebar = useCallback(() => setSidebarOpen(true), []);
  const closeSidebar = useCallback(() => setSidebarOpen(false), []);

  useLayoutEffect(() => {
    if (typeof window !== 'undefined') {
      // Adjust the height of the floating sidebar button wrapper.
      setDocumentHeight(document.body.scrollHeight);

      // Show the floating sidebar button only when the sidebar button at
      // the header disappears from the viewport.
      const onScroll = () => {
        const maybeFloatingWrapper = document.getElementsByClassName(
          styles.floatingSidebarButtonWrapper,
        );
        if (maybeFloatingWrapper.length > 0) {
          const floatingWrapper = maybeFloatingWrapper[0];
          if (window.scrollY < 64) {
            floatingWrapper.classList.remove(styles.visible);
          } else {
            floatingWrapper.classList.add(styles.visible);
          }
        }
      };
      onScroll();
      window.addEventListener('scroll', onScroll);
      return () => window.removeEventListener('scroll', onScroll);
    }

    return () => {};
  }, []);

  return (
    <>
      <div
        className={styles.floatingSidebarButtonWrapper}
        style={{ height: documentHeight }}
      >
        <StickyBox className={styles.floatingSidebarButton} offsetTop={16}>
          <Button onClick={openSidebar}>
            <MenuOutlined />
          </Button>
        </StickyBox>
      </div>
      <div className={styles.wrapper}>
        <Header className={styles.header}>
          <Link to="/" title="Home">
            <Logo
              className={styles.logo}
              role="navigation"
              label="Home"
              textColor="#ffffff"
              style={{ verticalAlign: 'top' }}
            />
          </Link>
          <Menu
            theme="dark"
            mode="horizontal"
            selectedKeys={selectedKeys}
            className={styles.topMenu}
          >
            <Menu.Item key="news">
              <Link to="/news">News</Link>
            </Menu.Item>
            <Menu.Item key="docs">
              <Link to="/docs">Documentation</Link>
            </Menu.Item>
            <Menu.Item key="community">
              <Link to="/community">Community</Link>
            </Menu.Item>
          </Menu>
          <div className={styles.topMenuIcons}>
            <OutboundLink href="https://github.com/line/armeria">
              <GithubOutlined />
            </OutboundLink>
            <Link to="/s/slack">
              <SlackOutlined />
            </Link>
            <OutboundLink href="https://twitter.com/armeria_project">
              <TwitterOutlined />
            </OutboundLink>
          </div>
          <div className={styles.sidebarWrapper}>
            <Button
              className={styles.staticSidebarButton}
              onClick={openSidebar}
            >
              <MenuOutlined />
            </Button>
            <Drawer
              className={styles.sidebarDrawer}
              width={320}
              visible={sidebarOpen}
              closeIcon={
                <CloseCircleOutlined className={styles.sidebarCloseIcon} />
              }
              onClose={closeSidebar}
            >
              <nav>
                <Menu theme="dark" mode="vertical" selectedKeys={selectedKeys}>
                  <Menu.Item key="home">
                    <Link to="/">Home</Link>
                  </Menu.Item>
                  <Menu.Item key="news">
                    <Link to="/news">News</Link>
                  </Menu.Item>
                  <Menu.Item key="docs">
                    <Link to="/docs">Documentation</Link>
                  </Menu.Item>
                  <Menu.Item key="community">
                    <Link to="/community">Community</Link>
                  </Menu.Item>
                </Menu>
                <div className={styles.sidebarMenuIcons}>
                  <OutboundLink href="https://github.com/line/armeria">
                    <GithubOutlined />
                  </OutboundLink>
                  <Link to="/s/slack">
                    <SlackOutlined />
                  </Link>
                  <OutboundLink href="https://twitter.com/armeria_project">
                    <TwitterOutlined />
                  </OutboundLink>
                </div>
                <div className={styles.sidebarNewsletter}>
                  <Paragraph>Like what we&apos;re doing?</Paragraph>
                  <Mailchimp />
                </div>
                {props.extraSidebarContent ? (
                  <div className={styles.sidebarExtra}>
                    {props.extraSidebarContent}
                  </div>
                ) : null}
              </nav>
            </Drawer>
          </div>
        </Header>
      </div>
    </>
  );
};

export default HeaderComponent;
