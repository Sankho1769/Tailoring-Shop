package com.jollytailor;

import com.jollytailor.config.ProductionDataSourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ProductionConfigurationAndDatabaseTest {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Production resolver successfully parses Render postgres:// URL with credentials")
    void testResolveRenderPostgresUrl() {
        String renderUrl = "postgres://jolly_app:SecretPassword123@dpg-abc123-a.oregon-postgres.render.com:5432/jolly_tailor";
        ProductionDataSourceConfig.ResolvedDbConfig config = ProductionDataSourceConfig.resolve(renderUrl, null, null);

        assertNotNull(config);
        assertEquals("jdbc:postgresql://dpg-abc123-a.oregon-postgres.render.com:5432/jolly_tailor", config.jdbcUrl);
        assertEquals("jolly_app", config.username);
        assertEquals("SecretPassword123", config.password);
    }

    @Test
    @DisplayName("Production resolver preserves SSL and query parameters in Render postgresql:// URL")
    void testResolveRenderPostgresqlUrlWithParams() {
        String renderUrl = "postgresql://jolly_prod:ProdPass999@ep-cool-cloud.aws.neon.tech:5432/tailordb?sslmode=require";
        ProductionDataSourceConfig.ResolvedDbConfig config = ProductionDataSourceConfig.resolve(renderUrl, null, null);

        assertNotNull(config);
        assertEquals("jdbc:postgresql://ep-cool-cloud.aws.neon.tech:5432/tailordb?sslmode=require", config.jdbcUrl);
        assertEquals("jolly_prod", config.username);
        assertEquals("ProdPass999", config.password);
    }

    @Test
    @DisplayName("Production resolver accepts standard JDBC URL and separate credentials")
    void testResolveStandardJdbcUrl() {
        String jdbcUrl = "jdbc:postgresql://localhost:5432/jolly_tailor";
        ProductionDataSourceConfig.ResolvedDbConfig config = ProductionDataSourceConfig.resolve(jdbcUrl, "app_user", "MyDbPass!");

        assertNotNull(config);
        assertEquals("jdbc:postgresql://localhost:5432/jolly_tailor", config.jdbcUrl);
        assertEquals("app_user", config.username);
        assertEquals("MyDbPass!", config.password);
    }

    @Test
    @DisplayName("Production resolver throws clear exception when database configuration is missing")
    void testMissingDatabaseUrlThrowsException() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ProductionDataSourceConfig.resolve("", "", "")
        );
        assertTrue(ex.getMessage().contains("Production database configuration is missing"),
                "Expected clear error message on missing config, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Production resolver strictly disallows H2 database in production")
    void testH2UrlDisallowedInProduction() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ProductionDataSourceConfig.resolve("jdbc:h2:file:./data/jolly_tailor", "sa", "")
        );
        assertTrue(ex.getMessage().contains("H2 database is not allowed in production"),
                "Expected error rejecting H2 in production, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Verify server.address binds to 0.0.0.0 and port fallback is 8080")
    void testServerBindingAndPortConfiguration() {
        String address = environment.getProperty("server.address");
        assertEquals("0.0.0.0", address, "Server address must bind to 0.0.0.0 for Render port detection");

        String port = environment.getProperty("server.port");
        assertNotNull(port, "Server port must not be null");
        assertEquals("8080", port, "Default fallback server port must be 8080");
    }

    @Test
    @DisplayName("Verify test profile correctly uses in-memory H2 without attempting to write to disk")
    void testTestProfileDatasource() {
        String url = environment.getProperty("spring.datasource.url");
        assertNotNull(url);
        assertTrue(url.contains("jdbc:h2:mem:"), "Test profile must use in-memory database, not disk");
    }

    @Test
    @DisplayName("Flyway V5 migration file exists and standardizes shop settings")
    void testFlywayV5MigrationExists() {
        java.io.File v5 = new java.io.File("src/main/resources/db/migration/V5__standardize_shop_phone_settings.sql");
        assertTrue(v5.exists(), "Flyway migration V5 must exist");
        assertTrue(v5.length() > 0, "Flyway migration V5 must not be empty");
    }

    @Test
    @DisplayName("Production profile application-prod.yml specifies spring.sql.init.mode: never")
    void testProductionProfileSqlInitModeNever() throws Exception {
        java.io.File prodYaml = new java.io.File("src/main/resources/application-prod.yml");
        assertTrue(prodYaml.exists(), "application-prod.yml must exist");
        String content = java.nio.file.Files.readString(prodYaml.toPath());
        assertTrue(content.contains("mode: never"), "Production config must set spring.sql.init.mode: never");
        assertTrue(content.contains("enabled: true"), "Production config must enable Flyway");
    }
}
