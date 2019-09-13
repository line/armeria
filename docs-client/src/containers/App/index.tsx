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
import Grid from '@material-ui/core/Grid';
import Hidden from '@material-ui/core/Hidden';
import IconButton from '@material-ui/core/IconButton';
import List from '@material-ui/core/List';
import ListItem from '@material-ui/core/ListItem';
import ListItemText from '@material-ui/core/ListItemText';
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
import update from 'immutability-helper';
import React from 'react';
import Helmet from 'react-helmet';
import { hot } from 'react-hot-loader/root';
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

import GotoSelect from '../../components/GotoSelect';

import {
  extractSimpleArtifactVersion,
  Version,
  Versions,
} from '../../lib/versions';

if (process.env.WEBPACK_DEV === 'true') {
  // DocService must always be accessed at the URL with a trailing slash. In non-dev mode, the server redirects
  // automatically but for dev we do it here in Javascript.
  const path = window.location.pathname;
  if (!path.endsWith('/')) {
    window.location.pathname = `${path}/`;
  }
}

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
      height: '100vh',
      overflowY: 'auto',
    },
    content: {
      backgroundColor: theme.palette.background.default,
      flexGrow: 1,
      height: '100vh',
      minWidth: 0, // So the Typography noWrap works,
      overflowY: 'auto',
      padding: theme.spacing(2),
    },
    methodHeader: {
      backgroundColor: theme.palette.background.paper,
    },
    title: {
      [theme.breakpoints.up('md')]: {
        marginLeft: theme.spacing(3),
      },
    },
    toolbar: theme.mixins.toolbar,
    httpMethodCommon: {
      borderRadius: 3,
      border: 0,
      color: 'white',
      fontSize: theme.typography.body2.fontSize,
      height: 20,
      width: 80,
      textAlign: 'center',
    },
    httpMethodOptions: {
      background: '#FF8E53',
    },
    httpMethodGet: {
      background: '#6abe45',
    },
    httpMethodHead: {
      background: '#FE6B8B',
    },
    httpMethodPost: {
      background: '#1e91ca',
    },
    httpMethodPut: {
      background: '#824ea0',
    },
    httpMethodPatch: {
      background: '#e6cc1d',
    },
    httpMethodDelete: {
      background: '#ec1d23',
    },
    httpMethodTrace: {
      background: '#5d12ec',
    },
    mainHeader: {
      textDecoration: 'none',
      color: 'white',
    },
  });

interface State {
  mobileDrawerOpen: boolean;
  specification?: Specification;
  versions?: Versions;
  servicesSectionOpen: boolean;
  openServices: { [key: string]: boolean };
  enumsSectionOpen: boolean;
  structsSectionOpen: boolean;
  exceptionsOpen: boolean;
}

type Props = WithStyles<typeof styles> & RouteComponentProps<{}>;

interface AppDrawerProps extends WithStyles<typeof styles> {
  specification: Specification;
  navigateTo: (url: string) => void;
  httpMethodClass: (httpMethod: string) => string;
  servicesSectionOpen: boolean;
  openServices: { [key: string]: boolean };
  enumsSectionOpen: boolean;
  structsSectionOpen: boolean;
  exceptionsOpen: boolean;
  handleCollapse: (itemName: string) => void;
  handleServiceCollapse: (serviceName: string) => void;
}

function AppDrawer({
  navigateTo,
  httpMethodClass,
  specification,
  servicesSectionOpen,
  openServices,
  enumsSectionOpen,
  structsSectionOpen,
  exceptionsOpen,
  handleCollapse,
  handleServiceCollapse,
}: AppDrawerProps) {
  return (
    <List component="nav">
      {specification.getServices().length > 0 && (
        <>
          <ListItem button onClick={() => handleCollapse('services')}>
            <ListItemText disableTypography>
              <Typography variant="h5">Services</Typography>
            </ListItemText>
            {servicesSectionOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItem>
          <Collapse in={servicesSectionOpen} timeout="auto">
            {specification.getServices().map((service) => (
              <div key={service.name}>
                <ListItem
                  button
                  onClick={() => handleServiceCollapse(service.name)}
                >
                  <ListItemText>
                    <Typography variant="subtitle1">
                      <code>{simpleName(service.name)}</code>
                    </Typography>
                  </ListItemText>
                  {openServices[service.name] ? <ExpandLess /> : <ExpandMore />}
                </ListItem>
                <Collapse in={openServices[service.name]} timeout="auto">
                  {service.methods.map((method) => (
                    <ListItem
                      dense
                      key={`${service.name}/${method.name}/${method.httpMethod}`}
                      button
                      onClick={() =>
                        navigateTo(
                          `/methods/${service.name}/${method.name}/${method.httpMethod}`,
                        )
                      }
                    >
                      <Grid container alignItems="center" spacing={5}>
                        <Grid item xs={4}>
                          <Typography
                            className={httpMethodClass(method.httpMethod)}
                          >
                            {method.httpMethod}
                          </Typography>
                        </Grid>
                        <Grid item xs={8}>
                          <ListItemText
                            primaryTypographyProps={{
                              variant: 'body2',
                            }}
                          >
                            <code>{`${method.name}()`}</code>
                          </ListItemText>
                        </Grid>
                      </Grid>
                    </ListItem>
                  ))}
                </Collapse>
              </div>
            ))}
          </Collapse>
        </>
      )}
      {specification.getEnums().length > 0 && (
        <>
          <ListItem button onClick={() => handleCollapse('enums')}>
            <ListItemText disableTypography>
              <Typography variant="h5">Enums</Typography>
            </ListItemText>
            {enumsSectionOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItem>
          <Collapse in={enumsSectionOpen} timeout="auto">
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
                    variant: 'body2',
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
              <Typography variant="h5">Structs</Typography>
            </ListItemText>
            {structsSectionOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItem>
          <Collapse in={structsSectionOpen} timeout="auto">
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
                    variant: 'body2',
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
              <Typography variant="h5">Exceptions</Typography>
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
                    variant: 'body2',
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
    versions: undefined,
    servicesSectionOpen: true,
    openServices: {},
    enumsSectionOpen: true,
    structsSectionOpen: true,
    exceptionsOpen: true,
  };

  public componentWillMount() {
    this.initSpecification();
    this.initVersion();
  }

  public render() {
    const { classes } = this.props;
    const { specification } = this.state;
    const { versions } = this.state;

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
              variant="h6"
              color="inherit"
              noWrap
            >
              <a href="#" className={classes.mainHeader}>
                Armeria documentation service {''}
                {versions
                  ? extractSimpleArtifactVersion(
                      versions.getArmeriaArtifactVersion(),
                    )
                  : ''}
              </a>
            </Typography>
            <div style={{ flex: 1 }} />
            <GotoSelect
              specification={specification}
              navigateTo={this.navigateTo}
            />
          </Toolbar>
        </AppBar>
        <Hidden smDown>
          <Drawer variant="permanent" classes={{ paper: classes.drawerPaper }}>
            <div className={classes.toolbar} />
            <AppDrawer
              classes={classes}
              specification={specification}
              navigateTo={this.navigateTo}
              httpMethodClass={this.httpMethodClass}
              servicesSectionOpen={this.state.servicesSectionOpen}
              openServices={this.state.openServices}
              enumsSectionOpen={this.state.enumsSectionOpen}
              structsSectionOpen={this.state.structsSectionOpen}
              exceptionsOpen={this.state.exceptionsOpen}
              handleCollapse={this.handleCollapse}
              handleServiceCollapse={this.handleServiceCollapse}
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
              httpMethodClass={this.httpMethodClass}
              servicesSectionOpen={this.state.servicesSectionOpen}
              openServices={this.state.openServices}
              enumsSectionOpen={this.state.enumsSectionOpen}
              structsSectionOpen={this.state.structsSectionOpen}
              exceptionsOpen={this.state.exceptionsOpen}
              handleCollapse={this.handleCollapse}
              handleServiceCollapse={this.handleServiceCollapse}
            />
          </Drawer>
        </Hidden>
        <main className={classes.content}>
          <div className={classes.toolbar} />
          <Route
            exact
            path="/"
            render={(props) => <HomePage {...props} versions={versions} />}
          />
          <Route
            path="/enums/:name"
            render={(props) => (
              <EnumPage {...props} specification={specification} />
            )}
          />
          <Route
            path="/methods/:serviceName/:methodName/:httpMethod"
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

  private httpMethodClass = (httpMethod: string) => {
    const classes = this.props.classes;
    let httpMethodClass;
    if (httpMethod === 'OPTIONS') {
      httpMethodClass = classes.httpMethodOptions;
    }
    if (httpMethod === 'GET') {
      httpMethodClass = classes.httpMethodGet;
    }
    if (httpMethod === 'HEAD') {
      httpMethodClass = classes.httpMethodHead;
    }
    if (httpMethod === 'POST') {
      httpMethodClass = classes.httpMethodPost;
    }
    if (httpMethod === 'PUT') {
      httpMethodClass = classes.httpMethodPut;
    }
    if (httpMethod === 'PATCH') {
      httpMethodClass = classes.httpMethodPatch;
    }
    if (httpMethod === 'DELETE') {
      httpMethodClass = classes.httpMethodDelete;
    }
    if (httpMethod === 'TRACE') {
      httpMethodClass = classes.httpMethodTrace;
    }

    if (httpMethodClass === undefined) {
      throw new Error(`unsupported http method: ${httpMethod}`);
    }
    return `${classes.httpMethodCommon} ${httpMethodClass}`;
  };

  private initSpecification = async () => {
    const httpResponse = await fetch('specification.json');
    const specificationData: SpecificationData = await httpResponse.json();
    const specification = new Specification(specificationData);
    let openServices = {};
    specification.getServices().forEach((service) => {
      openServices = { ...openServices, [service.name]: true };
    });
    this.setState({ specification, openServices });
  };

  private initVersion = async () => {
    const httpResponse = await fetch('versions.json');
    const versionsData: Version[] = await httpResponse.json();
    const versions = new Versions(versionsData);
    this.setState({ versions });
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
          servicesSectionOpen: !this.state.servicesSectionOpen,
        });
        break;
      case 'enums':
        this.setState({
          enumsSectionOpen: !this.state.enumsSectionOpen,
        });
        break;
      case 'structs':
        this.setState({
          structsSectionOpen: !this.state.structsSectionOpen,
        });
        break;
      case 'exceptions':
        this.setState({
          exceptionsOpen: !this.state.exceptionsOpen,
        });
        break;
    }
  };

  private handleServiceCollapse = (serviceName: string) => {
    this.setState({
      openServices: update(this.state.openServices, {
        [serviceName]: { $set: !this.state.openServices[serviceName] },
      }),
    });
  };
}

export default withRouter(withStyles(styles)(hot(App)));
