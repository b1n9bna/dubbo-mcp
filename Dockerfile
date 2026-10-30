# Multi-stage build for Glama.ai and containerized MCP runtimes
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/dubbo-mcp.jar /app/dubbo-mcp.jar

# Run via STDIO JSON-RPC
ENTRYPOINT ["java", "-jar", "/app/dubbo-mcp.jar"]
