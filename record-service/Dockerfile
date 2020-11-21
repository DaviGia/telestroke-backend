FROM openjdk:8-jre-alpine

# set working directory
WORKDIR /app

# verticle file
ARG VERTICLE_FILE
ENV VERTICLE_FILE=${VERTICLE_FILE:-record-service.jar}

# Copy your fat jar to the container
COPY build/libs/$VERTICLE_FILE /app/app.jar

#expose port
EXPOSE 8006

# Launch the verticle
ENTRYPOINT exec java -jar /app/app.jar
