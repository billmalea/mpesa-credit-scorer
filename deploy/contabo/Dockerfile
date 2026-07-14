FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY lib/ lib/
COPY src/ src/
RUN test -f lib/com.flexvertex.multiverse-client-1.3.0.jar \
  || (echo "Missing FlexVertex client JARs in lib/. On the VPS run:" \
      && echo "  docker compose up -d flexvertex && ../../scripts/sync-flexvertex-libs.sh" \
      && exit 1)
RUN mvn -q -Pflexvertex -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /build/target/mpesa-credit-scorer-1.0.0-SNAPSHOT.jar /app/scorer.jar
COPY --from=build /build/lib/ /app/lib/
COPY policy.yml /app/policy.yml
COPY docs/FlexVertex_Mobile_Money_Credit_Policy_Guidelines.pdf /app/docs/FlexVertex_Mobile_Money_Credit_Policy_Guidelines.pdf
COPY deploy/contabo/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8091
ENTRYPOINT ["/app/entrypoint.sh"]
