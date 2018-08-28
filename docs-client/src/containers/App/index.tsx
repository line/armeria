/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import AppBar from '@material-ui/core/AppBar';
import Collapse from '@material-ui/core/Collapse';
import CssBaseline from '@material-ui/core/CssBaseline';
import Drawer from '@material-ui/core/Drawer';
import Hidden from '@material-ui/core/Hidden';
import IconButton from '@material-ui/core/IconButton';
import List from '@material-ui/core/List';
import ListItem from '@material-ui/core/ListItem';
import ListItemText from '@material-ui/core/ListItemText';
import ListSubheader from '@material-ui/core/ListSubheader';
import {
  createStyles,
  Theme,
  withStyles,
  WithStyles,
} from '@material-ui/core/styles';
import Toolbar from '@material-ui/core/Toolbar';
import Typography from '@material-ui/core/Typography';
import ExpandLess from '@material-ui/icons/ExpandLess';
import ExpandMore from '@material-ui/icons/ExpandMore';
import MenuIcon from '@material-ui/icons/Menu';
import React from 'react';
import Helmet from 'react-helmet';
import { hot } from 'react-hot-loader';
import { Route, RouteComponentProps, withRouter } from 'react-router-dom';

import EnumPage from '../EnumPage';
import HomePage from '../HomePage';
import MethodPage from '../MethodPage';
import StructPage from '../StructPage';

import {
  simpleName,
  Specification,
  SpecificationData,
} from '../../lib/specification';

const styles = (theme: Theme) =>
  createStyles({
    root: {
      flexGrow: 1,
      zIndex: 1,
      overflow: 'hidden',
      position: 'relative',
      display: 'flex',
    },
    appBar: {
      zIndex: theme.zIndex.drawer + 1,
    },
    drawerPaper: {
      position: 'relative',
      [theme.breakpoints.down('sm')]: {
        width: '80%',
      },
    },
    content: {
      backgroundColor: theme.palette.background.default,
      flexGrow: 1,
      height: '100vh',
      minWidth: 0, // So the Typography noWrap works,
      overflowY: 'auto',
      padding: theme.spacing.unit * 2,
    },
    methodHeader: {
      backgroundColor: theme.palette.background.paper,
    },
    title: {
      [theme.breakpoints.up('md')]: {
        marginLeft: theme.spacing.unit * 3,
      },
    },
    toolbar: theme.mixins.toolbar,
  });

interface State {
  mobileDrawerOpen: boolean;
  specification?: Specification;
  servicesOpen: boolean;
  enumsOpen: boolean;
  structsOpen: boolean;
  exceptionsOpen: boolean;
}

type Props = WithStyles<typeof styles> & RouteComponentProps<{}>;

interface AppDrawerProps extends WithStyles<typeof styles> {
  specification: Specification;
  navigateTo: (url: string) => void;
  servicesOpen: boolean;
  enumsOpen: boolean;
  structsOpen: boolean;
  exceptionsOpen: boolean;
  handleCollapse: (itemName: string) => void;
}

function AppDrawer({
  classes,
  navigateTo,
  specification,
  servicesOpen,
  enumsOpen,
  structsOpen,
  exceptionsOpen,
  handleCollapse,
}: AppDrawerProps) {
  return (
    <List component="nav">
      {specification.getServices().length > 0 && (
        <>
          <ListItem button onClick={() => handleCollapse('services')}>
            <ListItemText disableTypography>
              <Typography variant="headline">Services</Typography>
            </ListItemText>
            {servicesOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItem>
          <Collapse in={servicesOpen} timeout="auto">
            {specification.getServices().map((service) => (
              <div key={service.name}>
                <ListSubheader className={classes.methodHeader}>
                  <Typography variant="subheading">
                    <code>{simpleName(service.name)}</code>
                  </Typography>
                </ListSubheader>
                {service.methods.map((method) => (
                  <ListItem
                    dense
                    key={`${service.name}/${method.name}`}
                    button
                    onClick={() =>
                      navigateTo(`/methods/${service.name}/${method.name}`)
                    }
                  >
                    <ListItemText
                      inset
                      primaryTypographyProps={{
                        variant: 'body1',
                      }}
                    >
                      <code>{method.name}()</code>
                    </ListItemText>
                  </ListItem>
                ))}
              </div>
            ))}
          </Collapse>
        </>
      )}
      {specification.getEnums().length > 0 && (
        <>
          <ListItem button onClick={() => handleCollapse('enums')}>
            <ListItemText disableTypography>
              <Typography variant="headline">Enums</Typography>
            </ListItemText>
            {enumsOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItem>
          <Collapse in={enumsOpen} timeout="auto">
            {specification.getEnums().map((enm) => (
              <ListItem
                dense
                key={enm.name}
                button
                onClick={() => navigateTo(`/enums/${enm.name}`)}
              >
                <ListItemText
                  inset
                  primaryTypographyProps={{
                    variant: 'body1',
                  }}
                >
                  <code>{simpleName(enm.name)}</code>
                </ListItemText>
              </ListItem>
            ))}
          </Collapse>
        </>
      )}
      {specification.getStructs().length > 0 && (
        <>
          <ListItem button onClick={() => handleCollapse('structs')}>
            <ListItemText disableTypography>
              <Typography variant="headline">Structs</Typography>
            </ListItemText>
            {structsOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItem>
          <Collapse in={structsOpen} timeout="auto">
            {specification.getStructs().map((struct) => (
              <ListItem
                dense
                key={struct.name}
                button
                onClick={() => navigateTo(`/structs/${struct.name}`)}
              >
                <ListItemText
                  inset
                  primaryTypographyProps={{
                    variant: 'body1',
                  }}
                >
                  <code>{simpleName(struct.name)}</code>
                </ListItemText>
              </ListItem>
            ))}
          </Collapse>
        </>
      )}
      {specification.getExceptions().length > 0 && (
        <>
          <ListItem button onClick={() => handleCollapse('exceptions')}>
            <ListItemText disableTypography>
              <Typography variant="headline">Exceptions</Typography>
            </ListItemText>
            {exceptionsOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItem>
          <Collapse in={exceptionsOpen} timeout="auto">
            {specification.getExceptions().map((struct) => (
              <ListItem
                dense
                key={struct.name}
                button
                onClick={() => navigateTo(`/structs/${struct.name}`)}
              >
                <ListItemText
                  inset
                  primaryTypographyProps={{
                    variant: 'body1',
                  }}
                >
                  <code>{simpleName(struct.name)}</code>
                </ListItemText>
              </ListItem>
            ))}
          </Collapse>
        </>
      )}
    </List>
  );
}

class App extends React.PureComponent<Props, State> {
  public state: State = {
    mobileDrawerOpen: false,
    specification: undefined,
    servicesOpen: true,
    enumsOpen: true,
    structsOpen: true,
    exceptionsOpen: true,
  };

  public componentWillMount() {
    this.fetchSpecification();
  }

  public render() {
    const { classes } = this.props;
    const { specification } = this.state;

    if (!specification) {
      return null;
    }

    const { pathname, search } = this.props.location;
    if (pathname.startsWith('/method/')) {
      const redirectPath = `/methods${pathname.substring(
        pathname.indexOf('/', 2),
      )}`;
      this.props.history.push(`${redirectPath}${search || ''}`);
      return null;
    }
    if (pathname.startsWith('/namedType')) {
      const name = pathname.substring(pathname.indexOf('/', 2) + 1);
      const redirectBase = specification.getStructByName(name)
        ? '/structs/'
        : '/enums/';
      this.props.history.push(`${redirectBase}${name}${search || ''}`);
      return null;
    }

    return (
      <div className={classes.root}>
        <Helmet>
          <script src="injected.js" />
        </Helmet>
        <CssBaseline />
        <AppBar className={classes.appBar}>
          <Toolbar disableGutters>
            <Hidden mdUp>
              <IconButton color="inherit" onClick={this.toggleMobileDrawer}>
                <MenuIcon />
              </IconButton>
            </Hidden>
            <Typography
              className={classes.title}
              variant="title"
              color="inherit"
              noWrap
            >
              Armeria documentation service
            </Typography>
          </Toolbar>
        </AppBar>
        <Hidden smDown>
          <Drawer variant="permanent" classes={{ paper: classes.drawerPaper }}>
            <div className={classes.toolbar} />
            <AppDrawer
              classes={classes}
              specification={specification}
              navigateTo={this.navigateTo}
              servicesOpen={this.state.servicesOpen}
              enumsOpen={this.state.enumsOpen}
              structsOpen={this.state.structsOpen}
              exceptionsOpen={this.state.exceptionsOpen}
              handleCollapse={this.handleCollapse}
            />
          </Drawer>
        </Hidden>
        <Hidden mdUp>
          <Drawer
            variant="temporary"
            open={this.state.mobileDrawerOpen}
            onClose={this.toggleMobileDrawer}
            classes={{ paper: classes.drawerPaper }}
            ModalProps={{
              keepMounted: true,
            }}
          >
            <AppDrawer
              classes={classes}
              specification={specification}
              navigateTo={this.navigateTo}
              servicesOpen={this.state.servicesOpen}
              enumsOpen={this.state.enumsOpen}
              structsOpen={this.state.structsOpen}
              exceptionsOpen={this.state.exceptionsOpen}
              handleCollapse={this.handleCollapse}
            />
          </Drawer>
        </Hidden>
        <main className={classes.content}>
          <div className={classes.toolbar} />
          <Route exact path="/" component={HomePage} />
          <Route
            path="/enums/:name"
            render={(props) => (
              <EnumPage {...props} specification={specification} />
            )}
          />
          <Route
            path="/methods/:serviceName/:methodName"
            render={(props) => (
              <MethodPage {...props} specification={specification} />
            )}
          />
          <Route
            path="/structs/:name"
            render={(props) => (
              <StructPage {...props} specification={specification} />
            )}
          />
        </main>
      </div>
    );
  }

  private navigateTo = (to: string) => {
    const params = new URLSearchParams(this.props.location.search);
    params.delete('args');
    const url = params.has('http_headers_sticky')
      ? `${to}?${params.toString()}`
      : to;
    this.props.history.push(url);
    this.setState({
      mobileDrawerOpen: false,
    });
  };

  private fetchSpecification = async () => {
    const httpResponse = await fetch('specification.json');
    const specification: SpecificationData = await httpResponse.json();
    this.setState({
      specification: new Specification(specification),
    });
  };

  private toggleMobileDrawer = () => {
    this.setState({
      mobileDrawerOpen: !this.state.mobileDrawerOpen,
    });
  };

  private handleCollapse = (itemName: string) => {
    switch (itemName) {
      case 'services':
        this.setState({
          servicesOpen: !this.state.servicesOpen,
        });
        break;
      case 'enums':
        this.setState({
          enumsOpen: !this.state.enumsOpen,
        });
        break;
      case 'structs':
        this.setState({
          structsOpen: !this.state.structsOpen,
        });
        break;
      case 'exceptions':
        this.setState({
          exceptionsOpen: !this.state.exceptionsOpen,
        });
        break;
    }
  };
}

export default hot(module)(withRouter(withStyles(styles)(App)));
