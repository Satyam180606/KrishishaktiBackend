FROM gradle:9.2.1-jdk21 AS build

WORKDIR /app

COPY . .

RUN chmod +x gradlew

RUN ./gradlew installDist --no-daemon --stacktrace

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/install/krishishakti-backend /app

EXPOSE 8080

CMD ["/app/bin/krishishakti-backend"]
