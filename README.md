# Telestroke - Backend

The **Telestroke** project aims to create a system to assist doctors (specialist) and first aid personnel (operator) in the assessment of a stroke case gravity (using [NIHSS](https://en.wikipedia.org/wiki/National_Institutes_of_Health_Stroke_Scale)). The system enables the specialist to perform remote reporting, to reduce disease treatment time and improve the quality of the medical supply. This project is my master thesis in Computer Science: [Study and development of a remote reporting system](https://amslaurea.unibo.it/20501/).

The project is structured in 3 main components:
- [backend](https://github.com/DaviGia/telestroke-backend): The microservices backend that handles frontend interaction and implements the main application login
- [web-frontend](https://github.com/DaviGia/telestroke-web-frontend): The web application used by the specialist to:
  * implements a WebRTC peer that sends audio to the operator and receives video and audio feed from his/her
  * remotely guide the operator to assess the patient status (by talking to the operator while watching the patient from the operator's feed)
  * guide him/her to perform the medical report and decide the course of action to treat the stroke
- [android-frontend](https://github.com/DaviGia/telestroke-android-frontend): The Android application used by the operator from hands-free wearable device (e.g. Smartglasses):
  * implements a WebRTC peer sends video and audio feed and receives audio from the specialist and receives audio from his/her
  * can display brief information about the current action that the specialist is performing from his/her device

## Description

Vert.x microservices architecture to manage authentication, reporting sessions, WebRTC peers and storage.

## Configuration

Each microservice can be configured using one or more of the following options:

- resources/service.yml (required)
- resources/service-`VERTX_PROFILE`.yml
- `workingdir`/config/*.yml
- k8s configmap named: service-configmap
- k8s secrets named: service-secrets

To see the available configuration for each specific microservice, see the file `resources/service.yml` inside each gradle module.

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
