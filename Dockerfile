# Multi-stage Docker build for Nnomm Flash-Deal Claim Service
FROM gradle:8.12-jdk21-alpine AS builder

WORKDIR /home/gradle/project

# Copy Gradle build files first for layer caching
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

# Build the application distribution without running tests
RUN gradle installDist --no-daemon -x test

# Runtime stage using lightweight Eclipse Temurin JRE 21
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user for security
RUN addgroup -S nnomm && adduser -S nnomm -G nnomm

# Copy installed distribution from builder stage
COPY --from=builder /home/gradle/project/build/install/nnomm-flash-deal /app

RUN chown -R nnomm:nnomm /app

USER nnomm

EXPOSE 8080 8081

ENTRYPOINT ["/app/bin/nnomm-flash-deal"]
