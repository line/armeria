### Additional `server` properties

> ***Note:*** Not all Dropwizard configurations can be passed into the Armeria server.  Currently supported parameters are:   
>   - `maxQueuedRequests`
>   - `maxThreads`
>   - `maxRequestLength`
>   - `maxRequestLength`
>   - `idleThreadTimeout`
>   - `shutdownGracePeriod`

`jerseyEnabled` - To enable / disable Dropwizard Jersey resources. Default=true. If set to false, this does not disable Dropwizard's Admin servlet for metrics or healthchecks. 

`accessLogWriter` - Used to configure [Armeria's Access Log Writer](https://line.github.io/armeria/server-access-log.html#customizing-a-log-format).  

Common (**default**) / Combined

```yaml
server:
  type: armeria
  accessLogWriter:
    type: common  # or combined
```

Custom

```yaml
server:
  type: armeria
  accessLogWriter:
    type: custom
    format: "%d - sample - %s"
```

### Additional Connector properties

The default `port` is 8082, to not interfere with Dropwizard's default application or admin servlets. 

**armeria-https**
  - `keyCertChainFile` - The path to the key cert chain file
  - `selfSigned` - True if a certificate is self-signed. Default=false. 