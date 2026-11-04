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
    @DisplayName("Flyway V8 forward-only migration file exists for staff work and payments")
    void testFlywayV8MigrationExists() {
        java.io.File v8 = new java.io.File("src/main/resources/db/migration/V8__staff_work_and_payments.sql");
        assertTrue(v8.exists(), "Flyway migration V8 must exist");
        assertTrue(v8.length() > 0, "Flyway migration V8 must not be empty");
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

    @Test
    @DisplayName("Supabase profile application-supabase.yml specifies spring.sql.init.mode: never and enables Flyway")
    void testSupabaseProfileSqlInitModeNever() throws Exception {
        java.io.File supabaseYaml = new java.io.File("src/main/resources/application-supabase.yml");
        assertTrue(supabaseYaml.exists(), "application-supabase.yml must exist");
        String content = java.nio.file.Files.readString(supabaseYaml.toPath());
        assertTrue(content.contains("mode: never"), "Supabase config must set spring.sql.init.mode: never");
        assertTrue(content.contains("enabled: true"), "Supabase config must enable Flyway");
        assertTrue(content.contains("maximum-pool-size:"), "Supabase config must configure connection pool");
    }

    @Test
    @DisplayName("Production resolver parses Supabase pooler URL, decodes credentials, and enforces SSL")
    void testResolveSupabaseSessionPoolerUrl() {
        String supabaseUrl = "postgresql://postgres.proj123:MySecret%402026%21@aws-0-ap-south-1.pooler.supabase.com:5432/postgres";
        ProductionDataSourceConfig.ResolvedDbConfig config = ProductionDataSourceConfig.resolve(supabaseUrl, null, null);

        assertNotNull(config);
        assertEquals("postgres.proj123", config.username);
        assertEquals("MySecret@2026!", config.password, "Encoded password must be decoded properly");
        assertTrue(config.jdbcUrl.startsWith("jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres"));
        assertTrue(config.jdbcUrl.contains("sslmode=require"), "Supabase connection must enforce sslmode=require");
    }

    @Test
    @DisplayName("Production resolver adds prepareThreshold=0 for Supabase transaction pooler on port 6543")
    void testResolveSupabaseTransactionPooler() {
        String poolerUrl = "postgresql://postgres.proj123:pass@aws-0-ap-south-1.pooler.supabase.com:6543/postgres?sslmode=require";
        ProductionDataSourceConfig.ResolvedDbConfig config = ProductionDataSourceConfig.resolve(poolerUrl, null, null);

        assertNotNull(config);
        assertTrue(config.jdbcUrl.contains("prepareThreshold=0"), "Port 6543 pooler must include prepareThreshold=0");
    }
}
