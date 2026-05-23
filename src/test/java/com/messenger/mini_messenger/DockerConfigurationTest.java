package com.messenger.mini_messenger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerConfigurationTest {

    @Test
    void composeDefinesBackendWithPostgresAndRedisServiceHosts() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yaml"));

        assertTrue(compose.contains("backend:"), "compose.yaml must define a backend service");
        assertTrue(compose.contains("SPRING_DATASOURCE_URL"), "backend container must connect to postgres service host via SPRING_DATASOURCE_URL");
        assertTrue(compose.contains("SPRING_DATA_REDIS_URL"), "backend container must connect to redis service host via SPRING_DATA_REDIS_URL");
        assertTrue(compose.contains("POSTGRES_DB"), "database service must expose POSTGRES_DB");
        assertTrue(compose.contains("pg_isready"), "database healthcheck must use PostgreSQL readiness probe");
        assertTrue(compose.contains("Dockerfile"), "backend service must build from Dockerfile");
    }

    @Test
    void redisCanRunWithOptionalPasswordFromEnv() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yaml"));

        assertTrue(compose.contains("requirepass"), "Redis command should use requirepass when REDIS_PASSWORD is set");
        assertTrue(compose.contains("REDIS_PASSWORD"), "Redis password must be controlled by REDIS_PASSWORD");
    }

    @Test
    void dockerfileBuildsSpringBootJar() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(dockerfile.contains("mvnw"), "Dockerfile should build with Maven wrapper");
        assertTrue(dockerfile.contains("java"), "Dockerfile should run the Spring Boot jar with java");
    }
}
