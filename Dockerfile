FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /src

COPY lib ./lib
COPY src/main/java ./src/main/java
COPY src/main/resources ./src/main/resources

RUN set -eux; \
    mkdir -p /build/classes /build/lib; \
    cp lib/*.jar /build/lib/; \
    CLASSPATH="$(printf ':%s' lib/*.jar)"; \
    CLASSPATH="${CLASSPATH#:}"; \
    find src/main/java -name "*.java" -print0 | xargs -0 javac --release 21 -encoding UTF-8 -d /build/classes -cp "${CLASSPATH}"; \
    cp -r src/main/resources/* /build/classes/

FROM eclipse-temurin:21-jre-jammy AS runtime-base

WORKDIR /app

COPY --from=builder /build/classes ./classes
COPY --from=builder /build/lib ./lib

RUN mkdir -p config cache logs models llamacpp downloads

EXPOSE 8080 8070 11434 1234 8075

VOLUME ["/app/config", "/app/cache", "/app/logs", "/app/models", "/app/llamacpp", "/app/downloads"]

ENV JAVA_OPTS="-Xms512m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -classpath './classes:./lib/*' org.mark.llamacpp.server.LlamaServer"]

FROM runtime-base AS runtime-vulkan

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        libvulkan1 \
        mesa-vulkan-drivers \
        vulkan-tools \
        libdrm2 \
        libx11-6 \
        libx11-xcb1 \
        libxcb1 \
        libxcb-dri3-0 \
        libxxf86vm1 && \
    rm -rf /var/lib/apt/lists/*

ENV VK_LAYER_PATH="/usr/share/vulkan/explicit_layer.d:/usr/share/vulkan/implicit_layer.d" \
    LD_LIBRARY_PATH="/usr/local/lib:/usr/local/lib64:/usr/lib:/usr/lib64:/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH}"

FROM rocm/dev-ubuntu-24.04:7.0-complete AS runtime-rocm

WORKDIR /app

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless \
        ca-certificates \
        libvulkan1 \
        mesa-vulkan-drivers \
        vulkan-tools && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/classes ./classes
COPY --from=builder /build/lib ./lib

RUN mkdir -p config cache logs models llamacpp downloads

EXPOSE 8080 8070 11434 1234 8075

VOLUME ["/app/config", "/app/cache", "/app/logs", "/app/models", "/app/llamacpp", "/app/downloads"]

ENV ROCM_PATH="/opt/rocm" \
    HIP_PATH="/opt/rocm" \
    PATH="/opt/rocm/bin:/opt/rocm/llvm/bin:${PATH}" \
    LD_LIBRARY_PATH="/opt/rocm/lib:/opt/rocm/lib64:/usr/local/lib:/usr/local/lib64:/usr/lib:/usr/lib64:/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH}" \
    JAVA_OPTS="-Xms512m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -classpath './classes:./lib/*' org.mark.llamacpp.server.LlamaServer"]

FROM runtime-vulkan AS final
