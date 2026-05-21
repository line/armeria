---
name: xds-dev
description: Build and test all xDS-related modules. Useful during xDS development to verify changes compile and pass tests across the full xDS module set.
---

# xDS Development

Build and test commands for all xDS-related modules.

## Commands

### Test all xDS modules

```
./gradlew --parallel -Pretry=true :xds:test :xds-api:test :xds-validator:test :it:xds-client:test :it:xds-controlplane-api:test :it:xds-no-validation:test :it:xds-istio:test
```
