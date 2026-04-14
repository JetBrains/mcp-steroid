FROM gradle:8.11.1-jdk21

USER root

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        jq \
        unzip \
        zip \
        rsync \
        docker.io \
        gh \
        make \
        nodejs \
        npm \
        python3 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

CMD ["bash"]
