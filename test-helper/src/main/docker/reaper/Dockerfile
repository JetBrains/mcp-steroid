FROM registry.jetbrains.team/p/ij/docker-hub/docker:27-cli
RUN apk add --no-cache socat
COPY reaper.sh /reaper.sh
RUN chmod +x /reaper.sh
EXPOSE 8080
ENTRYPOINT ["/reaper.sh"]
