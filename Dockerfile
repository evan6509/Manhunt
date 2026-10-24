# syntax=docker/dockerfile:1
# One image runs either the Velocity proxy or a Fabric backend.
FROM eclipse-temurin:25-jdk AS build
ARG MANHUNT_VERSION=1.0.0
ENV MANHUNT_VERSION=${MANHUNT_VERSION}
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon --max-workers=1 -Pminecraft_version=26.2 build \
    && mkdir /out \
    && cp "build/libs/manhunt-${MANHUNT_VERSION}+mc26.2.jar" /out/manhunt.jar \
    && cp "velocity-plugin/build/libs/manhunt-velocity-${MANHUNT_VERSION}+mc26.2.jar" /out/manhunt-velocity.jar

FROM eclipse-temurin:25-jdk AS server-install
ARG FABRIC_INSTALLER_VERSION=1.1.2
ARG MINECRAFT_VERSION=26.2
ARG FABRIC_LOADER_VERSION=0.19.3
ARG FABRIC_API_VERSION=0.154.2+26.2
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /install/mods \
    && curl -fsSL -o /install/fabric-installer.jar \
       "https://maven.fabricmc.net/net/fabricmc/fabric-installer/${FABRIC_INSTALLER_VERSION}/fabric-installer-${FABRIC_INSTALLER_VERSION}.jar" \
    && java -jar /install/fabric-installer.jar server \
       -mcversion "${MINECRAFT_VERSION}" -loader "${FABRIC_LOADER_VERSION}" -dir /install -downloadMinecraft \
    && rm /install/fabric-installer.jar \
    && curl -fsSL -o /install/mods/fabric-api.jar \
       "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar"
# Pinned Modrinth version CsEpiziv, with its published SHA-512 checksum.
RUN curl -fsSL -o /install/mods/FabricProxy-Lite-2.12.0.jar \
       "https://cdn.modrinth.com/data/8dI2tmqs/versions/CsEpiziv/FabricProxy-Lite-2.12.0.jar" \
    && echo "b479c3ed1fe83929cad40e5c925ae2702da879b88a0271a24266cd21ecc037953f347cbe61ac7b7334e087544ee2ce5bf1f041fc3e64f50474404ad564c146f7  /install/mods/FabricProxy-Lite-2.12.0.jar" | sha512sum -c -
COPY --from=build /out/manhunt.jar /install/mods/manhunt.jar

FROM eclipse-temurin:25-jdk AS proxy-install
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# Official PaperMC Velocity 4.0.0 build 6; content-addressed and verified.
RUN curl -fsSL -o /velocity.jar \
       "https://fill-data.papermc.io/v1/objects/4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70/velocity-4.0.0-6.jar" \
    && echo "4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70  /velocity.jar" | sha256sum -c -

FROM eclipse-temurin:25-jdk AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=server-install /install /opt/manhunt/server
COPY --from=proxy-install /velocity.jar /opt/manhunt/velocity.jar
COPY --from=build /out/manhunt-velocity.jar /opt/manhunt/manhunt-velocity.jar
COPY docker/controller.py docker/entrypoint.sh /opt/manhunt/
RUN chmod +x /opt/manhunt/entrypoint.sh
ENV MODE=server SERVER_ID=a MC_MEMORY=3G SHARED_DIR=/manhunt
WORKDIR /opt/manhunt
ENTRYPOINT ["/opt/manhunt/entrypoint.sh"]
