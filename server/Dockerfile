FROM golang:1.22.12-bookworm AS build

WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags='-s -w' -o /out/cauce ./cmd/cauce

FROM scratch
COPY --from=build /out/cauce /cauce
ENTRYPOINT ["/cauce"]
