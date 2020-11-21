# Introduction

Telestroke - Backend

## Microservices configuration

Vert.x microservices can be configured with the environment variable: `VERTX_PROFILE`.

Other available configuration options are:

- resources/service.yml (required)
- resources/service-`VERTX_PROFILE`.yml
- `workingdir`/config/*.yml
- k8s configmap named: service-configmap
- k8s secrets named: service-secrets

## Distribution

### Jar

``` bash
gradlew makeDist
java -jar %jar_path%
#or
gradlew run
```

The `makeDist` gradle task will build the services as jars inside the folder `dist`.

### Docker

``` bash
# build images
gradlew buildImage

# deploy
docker-compose up -d
```

## Running ports

|Service|Port|
|---|---|
|gateway|8001|
|auth-service|8002|
|registry-service|8003|
|session-service|8004|
|peer-service|8005|
|record-service|8006|
|frontend|8080|
|peerjs-server|9000|

## Known issues

- If you're running `Docker Desktop` make sure to `Expose daemon on tcp://localhost:2375 without TLS`. Otherwise gradle `buildImage` task will fail.

- If you're running `Docker Desktop` and `docker-compose` command cannot create volume, use `docker volume prune` and retry.

- If `mongodb` skips initialization scripts execution, make sure that the database is empty (prune docker volumes if necessary).

## Demo information

The `docker-compose` is already setup to insert initial data to the database. The following table shows the available users:

|Username|Password|
|--|--|
|admin|test|
|guest|guest|