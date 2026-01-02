FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Etc/UTC

# Core OS + tooling for OT lab + Java (j60870) + build chain (lib60870-C)
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl wget git gnupg tzdata \
    bash coreutils procps psmisc \
    vim nano less \
    iproute2 iputils-ping net-tools netcat-openbsd dnsutils \
    tcpdump tshark \
    openjdk-17-jre-headless \
    build-essential cmake pkg-config \
    libssl-dev \
    nmap \
  && rm -rf /var/lib/apt/lists/*

# Workspace layout
WORKDIR /opt

# --- COPY your custom lib60870 into the image ---
COPY lib60870 /opt/lib60870

# Build and install lib60870-C
WORKDIR /opt/lib60870/lib60870-C

 RUN mkdir -p build \
  && cd /opt/lib60870/lib60870-C \
  && mkdir -p build && cd build \
  && cmake .. -DCMAKE_BUILD_TYPE=Release \
  && make -j"$(nproc)" \
  && make install \
  && ldconfig

# Non-root user (optional but recommended)
RUN useradd -m -s /bin/bash otuser \
  && chown -R otuser:otuser /opt/*
USER otuser

ENV JAVA_OPTS="-Xms64m -Xmx256m"
ENV PATH="/opt/ot/bin:${PATH}"

CMD ["bash", "-lc", "echo '[ot-ws] Ready. lib60870-C installed. Workspace: /opt/ot' && sleep infinity"]
