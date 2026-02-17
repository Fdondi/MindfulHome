FROM eclipse-temurin:21-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV GRADLE_HOME=/opt/gradle
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${GRADLE_HOME}/bin"

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    unzip \
    git \
    && rm -rf /var/lib/apt/lists/*

# Install Gradle
RUN curl -fsSL https://services.gradle.org/distributions/gradle-9.2.1-bin.zip \
        -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv /opt/gradle-9.2.1 ${GRADLE_HOME} && \
    rm /tmp/gradle.zip

# Install Android SDK command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
        -o /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
        "platforms;android-36" \
        "build-tools;36.0.0" \
        "platform-tools"

WORKDIR /project
