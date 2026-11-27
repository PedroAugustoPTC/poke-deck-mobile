# syntax=docker/dockerfile:1
# Tudo compila aqui dentro (JDK, Android SDK, Gradle): nada é instalado no PC. Só o APK sai, em dist\.
#   docker build --target export --output dist .            (debug, para testar)
#   release: o workflow do GitHub assina com a chave dos secrets.

FROM eclipse-temurin:17-jdk AS sdk
ENV ANDROID_HOME=/sdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
# commandlinetools 11076708 = sdkmanager 12.0
RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/c.zip \
 && mkdir -p /sdk/cmdline-tools && unzip -q /tmp/c.zip -d /sdk/cmdline-tools && mv /sdk/cmdline-tools/cmdline-tools /sdk/cmdline-tools/latest && rm /tmp/c.zip \
 && yes | /sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
 && /sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;35.0.0" > /dev/null
RUN curl -fsSL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o /tmp/g.zip && unzip -q /tmp/g.zip -d /opt && rm /tmp/g.zip
ENV PATH=/opt/gradle-8.11.1/bin:$PATH

FROM sdk AS build
WORKDIR /src
COPY . .
ARG VERSION=0.0.0
# O cache do Gradle fica num volume do Docker (rebuilds rápidos), não no PC.
RUN --mount=type=cache,target=/root/.gradle \
    gradle --no-daemon --console=plain test assembleDebug -PversionName=$VERSION

FROM scratch AS export
COPY --from=build /src/app/build/outputs/apk/debug/app-debug.apk /PokeDeck-debug.apk
COPY --from=build /src/app/build/reports/tests /tests
