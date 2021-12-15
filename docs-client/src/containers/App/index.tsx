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
import { createStyles, makeStyles, Theme } from '@material-ui/core/styles';
import Toolbar from '@material-ui/core/Toolbar';
import Tooltip from '@material-ui/core/Tooltip';
import Typography from '@material-ui/core/Typography';
import ExpandLess from '@material-ui/icons/ExpandLess';
import ExpandMore from '@material-ui/icons/ExpandMore';
import MenuIcon from '@material-ui/icons/Menu';
import React, { useCallback, useEffect, useReducer, useState } from 'react';
import { Helmet } from 'react-helmet';
import { hot } from 'react-hot-loader/root';
import { Route, RouteComponentProps, withRouter } from 'react-router-dom';

import EnumPage from '../EnumPage';
import HomePage from '../HomePage';
import MethodPage from '../MethodPage';
import StructPage from '../StructPage';

import {
  packageName,
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

const useStyles = makeStyles((theme: Theme) =>
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
  }),
);

type Props = RouteComponentProps<{}>;

interface AppDrawerProps {
  specification: Specification;
  navigateTo: (url: string) => void;
  httpMethodClass: (httpMethod: string) => string;
  servicesSectionOpen: boolean;
  openServices: { [key: string]: boolean };
  enumsSectionOpen: boolean;
  structsSectionOpen: boolean;
  exceptionsOpen: boolean;
  toggleServicesOpen: React.Dispatch<unknown>;
  toggleEnumsOpen: React.Dispatch<unknown>;
  toggleStructsOpen: React.Dispatch<unknown>;
  toggleExceptionsOpen: React.Dispatch<unknown>;
  handleServiceCollapse: (serviceName: string) => void;
}

const AppDrawer: React.FunctionComponent<AppDrawerProps> = ({
  navigateTo,
  httpMethodClass,
  specification,
  servicesSectionOpen,
  openServices,
  enumsSectionOpen,
  structsSectionOpen,
  exceptionsOpen,
  toggleServicesOpen,
  toggleEnumsOpen,
  toggleStructsOpen,
  toggleExceptionsOpen,
  handleServiceCollapse,
}) => {
  return (
    <List component="nav">
      {specification.getServices().length > 0 && (
        <>
          <ListItem button onClick={toggleServicesOpen}>
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
                    <Typography display="inline" variant="body2">
                      <code>
                        {specification.hasUniqueServiceNames()
                          ? ''
                          : `${packageName(service.name)}.`}
                      </code>
                    </Typography>
                    <Typography display="inline" variant="subtitle1">
                      <Tooltip title={`${service.name}`} placement="top">
                        <code>{simpleName(service.name)}</code>
                      </Tooltip>
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
                      <Grid container alignItems="center" spacing={2}>
                        <Grid item xs="auto">
                          <Typography
                            className={httpMethodClass(method.httpMethod)}
                          >
                            {method.httpMethod}
                          </Typography>
                        </Grid>
                        <Grid item xs>
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
          <ListItem button onClick={toggleEnumsOpen}>
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
                  <code>
                    {specification.hasUniqueEnumNames()
                      ? simpleName(enm.name)
                      : enm.name}
                  </code>
                </ListItemText>
              </ListItem>
            ))}
          </Collapse>
        </>
      )}
      {specification.getStructs().length > 0 && (
        <>
          <ListItem button onClick={toggleStructsOpen}>
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
                  <code>
                    {specification.hasUniqueStructNames()
                      ? simpleName(struct.name)
                      : struct.name}
                  </code>
                </ListItemText>
              </ListItem>
            ))}
          </Collapse>
        </>
      )}
      {specification.getExceptions().length > 0 && (
        <>
          <ListItem button onClick={toggleExceptionsOpen}>
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
                  <code>
                    {specification.hasUniqueStructNames()
                      ? simpleName(struct.name)
                      : struct.name}
                  </code>
                </ListItemText>
              </ListItem>
            ))}
          </Collapse>
        </>
      )}
    </List>
  );
};

interface OpenServices {
  [name: string]: boolean;
}

const toggle = (current: boolean) => !current;

const App: React.FunctionComponent<Props> = (props) => {
  const [mobileDrawerOpen, setMobileDrawerOpen] = useState(false);
  const [specification, setSpecification] = useState<
    Specification | undefined
  >();
  const [versions, setVersions] = useState<Versions | undefined>();
  const [openServices, toggleOpenService] = useReducer(
    (current: OpenServices, serviceName: string) => ({
      ...current,
      [serviceName]: !current[serviceName],
    }),
    {},
  );
  const [servicesOpen, toggleServicesOpen] = useReducer(toggle, true);
  const [enumsOpen, toggleEnumsOpen] = useReducer(toggle, true);
  const [structsOpen, toggleStructsOpen] = useReducer(toggle, true);
  const [exceptionsOpen, toggleExceptionsOpen] = useReducer(toggle, true);

  useEffect(() => {
    (async () => {
      const httpResponse = await fetch('specification.json');
      const specificationData: SpecificationData = await httpResponse.json();
      const initialSpecification = new Specification(specificationData);
      initialSpecification.getServices().forEach((service) => {
        toggleOpenService(service.name);
      });
      setSpecification(initialSpecification);
    })();
  }, []);

  useEffect(() => {
    (async () => {
      const httpResponse = await fetch('versions.json');
      const versionsData: Version[] = await httpResponse.json();
      const initialVersions = new Versions(versionsData);
      setVersions(initialVersions);
    })();
  }, []);

  const classes = useStyles();

  const navigateTo = useCallback(
    (to: string) => {
      props.history.push(to);
      setMobileDrawerOpen(false);
    },
    [props.history],
  );

  const toggleMobileDrawer = useCallback(() => {
    setMobileDrawerOpen(!mobileDrawerOpen);
  }, [mobileDrawerOpen]);

  const httpMethodClass = useCallback(
    (httpMethod: string) => {
      let methodClass;
      if (httpMethod === 'OPTIONS') {
        methodClass = classes.httpMethodOptions;
      }
      if (httpMethod === 'GET') {
        methodClass = classes.httpMethodGet;
      }
      if (httpMethod === 'HEAD') {
        methodClass = classes.httpMethodHead;
      }
      if (httpMethod === 'POST') {
        methodClass = classes.httpMethodPost;
      }
      if (httpMethod === 'PUT') {
        methodClass = classes.httpMethodPut;
      }
      if (httpMethod === 'PATCH') {
        methodClass = classes.httpMethodPatch;
      }
      if (httpMethod === 'DELETE') {
        methodClass = classes.httpMethodDelete;
      }
      if (httpMethod === 'TRACE') {
        methodClass = classes.httpMethodTrace;
      }

      if (methodClass === undefined) {
        throw new Error(`unsupported http method: ${httpMethod}`);
      }
      return `${classes.httpMethodCommon} ${methodClass}`;
    },
    [classes],
  );

  if (!specification) {
    return null;
  }

  const { pathname, search } = props.location;
  if (pathname.startsWith('/method/')) {
    const redirectPath = `/methods${pathname.substring(
      pathname.indexOf('/', 2),
    )}`;
    props.history.push(`${redirectPath}${search || ''}`);
    return null;
  }
  if (pathname.startsWith('/namedType')) {
    const name = pathname.substring(pathname.indexOf('/', 2) + 1);
    const redirectBase = specification.getStructByName(name)
      ? '/structs/'
      : '/enums/';
    props.history.push(`${redirectBase}${name}${search || ''}`);
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
            <IconButton color="inherit" onClick={toggleMobileDrawer}>
              <MenuIcon />
            </IconButton>
          </Hidden>
          <Typography
            className={classes.title}
            variant="h6"
            color="inherit"
            noWrap
          >
            <span className={classes.mainHeader}>
              Armeria documentation service
              {versions
                ? ` ${extractSimpleArtifactVersion(
                    versions.getArmeriaArtifactVersion(),
                  )}`
                : ''}
            </span>
          </Typography>
          <div style={{ flex: 1 }} />
          <GotoSelect specification={specification} navigateTo={navigateTo} />
        </Toolbar>
      </AppBar>
      <Hidden smDown>
        <Drawer variant="permanent" classes={{ paper: classes.drawerPaper }}>
          <div className={classes.toolbar} />
          <AppDrawer
            specification={specification}
            navigateTo={navigateTo}
            httpMethodClass={httpMethodClass}
            servicesSectionOpen={servicesOpen}
            openServices={openServices}
            enumsSectionOpen={enumsOpen}
            structsSectionOpen={structsOpen}
            exceptionsOpen={exceptionsOpen}
            toggleServicesOpen={toggleServicesOpen}
            toggleEnumsOpen={toggleEnumsOpen}
            toggleStructsOpen={toggleStructsOpen}
            toggleExceptionsOpen={toggleExceptionsOpen}
            handleServiceCollapse={toggleOpenService}
          />
        </Drawer>
      </Hidden>
      <Hidden mdUp>
        <Drawer
          variant="temporary"
          open={mobileDrawerOpen}
          onClose={toggleMobileDrawer}
          classes={{ paper: classes.drawerPaper }}
          ModalProps={{
            keepMounted: true,
          }}
        >
          <AppDrawer
            specification={specification}
            navigateTo={navigateTo}
            httpMethodClass={httpMethodClass}
            servicesSectionOpen={servicesOpen}
            openServices={openServices}
            enumsSectionOpen={enumsOpen}
            structsSectionOpen={structsOpen}
            exceptionsOpen={exceptionsOpen}
            toggleServicesOpen={toggleServicesOpen}
            toggleEnumsOpen={toggleEnumsOpen}
            toggleStructsOpen={toggleStructsOpen}
            toggleExceptionsOpen={toggleExceptionsOpen}
            handleServiceCollapse={toggleOpenService}
          />
        </Drawer>
      </Hidden>
      <main className={classes.content}>
        <div className={classes.toolbar} />
        <Route
          exact
          path="/"
          render={(p) => <HomePage {...p} versions={versions} />}
        />
        <Route
          path="/enums/:name"
          render={(p) => <EnumPage {...p} specification={specification} />}
        />
        <Route
          path="/methods/:serviceName/:methodName/:httpMethod"
          render={(p) => <MethodPage {...p} specification={specification} />}
        />
        <Route
          path="/structs/:name"
          render={(p) => <StructPage {...p} specification={specification} />}
        />
      </main>
    </div>
  );
};

export default withRouter(hot(App));
