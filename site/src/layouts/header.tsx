import {
  GithubOutlined,
  TwitterOutlined,
  MenuOutlined,
  SlackOutlined,
} from '@ant-design/icons';
import { WindowLocation } from '@reach/router';
import { Layout, Menu, Drawer, Button } from 'antd';
import Link from 'gatsby-link';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React, { useState, useCallback } from 'react';

import Logo from '../components/logo';

import styles from './header.module.less';

const { Header } = Layout;

interface HeaderComponentProps {
  location: WindowLocation;
}

const selectableKeysAndRegexes = {
  news: /\/news(\/|$)/,
  guides: /\/guides(\/|$)/,
  docs: /\/docs(\/|$)/,
  community: /\/community(\/|$)/,
  home: /.?/,
};

const HeaderComponent: React.FC<HeaderComponentProps> = props => {
  const [verticalMenuOpen, setVerticalMenuOpen] = useState(false);

  const selectedKeyAndRegex = Object.entries(
    selectableKeysAndRegexes,
  ).find(([, regexp]) => props.location?.pathname?.match(regexp));

  const selectedKeys = selectedKeyAndRegex ? [selectedKeyAndRegex[0]] : [];

  return (
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
          className={styles.horizontalMenu}
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
        <div className={styles.horizontalMenuIcons}>
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
        <div className={styles.verticalMenuWrapper}>
          <Button
            className={styles.verticalMenuOpenButton}
            onClick={useCallback(() => setVerticalMenuOpen(true), [])}
          >
            <MenuOutlined />
          </Button>
          <Drawer
            className={styles.verticalMenuDrawer}
            visible={verticalMenuOpen}
            onClose={useCallback(() => setVerticalMenuOpen(false), [])}
          >
            <Menu
              theme="dark"
              mode="vertical"
              selectedKeys={selectedKeys}
              className={styles.verticalMenu}
            >
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
            <div className={styles.verticalMenuIcons}>
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
          </Drawer>
        </div>
      </Header>
    </div>
  );
};

export default HeaderComponent;
