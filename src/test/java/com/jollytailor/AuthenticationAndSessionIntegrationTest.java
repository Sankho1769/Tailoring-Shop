package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationAndSessionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;

    @Test
    @DisplayName("CSRF endpoint returns valid token and header name")
    void testCsrfEndpoint() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName", anyOf(is("X-CSRF-TOKEN"), is("X-XSRF-TOKEN"))));
    }

    @Test
    @DisplayName("Successful Admin login creates session and returns ADMIN role")
    void testSuccessfulAdminLogin() throws Exception {
        Map<String, String> credentials = Map.of(
                "username", "testadmin",
                "password", "TestAdminPassword123!"
        );

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(credentials)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("testadmin")))
                .andExpect(jsonPath("$.role", is("ADMIN")))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(session, "Session must be created upon login");

        // Verify session allows access to /api/auth/me
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("testadmin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @Test
    @DisplayName("Successful Tailor login creates session and returns TAILOR role")
    void testSuccessfulTailorLogin() throws Exception {
        Map<String, String> credentials = Map.of(
                "username", "testtailor",
                "password", "TestTailorPassword123!"
        );

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(credentials)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("testtailor")))
                .andExpect(jsonPath("$.role", is("TAILOR")));
    }

    @Test
    @DisplayName("Login with incorrect password fails with 401 Unauthorized")
    void testLoginIncorrectPassword() throws Exception {
        Map<String, String> credentials = Map.of(
                "username", "testadmin",
                "password", "WrongSecretPassword999!"
        );

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(credentials)))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Invalid username or password"));
    }

    @Test
    @DisplayName("Login with unknown username fails with 401 Unauthorized")
    void testLoginUnknownUser() throws Exception {
        Map<String, String> credentials = Map.of(
                "username", "non_existent_staff",
                "password", "Password123456!"
        );

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(credentials)))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Invalid username or password"));
    }

    @Test
    @DisplayName("Login with inactive (deactivated) account is rejected with 403 Forbidden")
    void testLoginInactiveAccount() throws Exception {
        // Seed an inactive user
        String username = "disabled_tailor";
        jdbc.update("DELETE FROM app_users WHERE username=?", username);
        jdbc.update("INSERT INTO app_users(username, password_hash, role, active) VALUES(?,?,?,?)",
                username, encoder.encode("DisabledPassword123!"), "TAILOR", false);

        Map<String, String> credentials = Map.of(
                "username", username,
                "password", "DisabledPassword123!"
        );

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(credentials)))
                .andExpect(status().isForbidden())
                .andExpect(status().reason(containsString("inactive")));
    }

    @Test
    @DisplayName("State-mutating login POST without CSRF token is blocked with 403 Forbidden")
    void testLoginMissingCsrfToken() throws Exception {
        Map<String, String> credentials = Map.of(
                "username", "testadmin",
                "password", "TestAdminPassword123!"
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(credentials)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tailor session can access Tailor workspace APIs but is blocked from Admin APIs")
    void testTailorRoleRestrictions() throws Exception {
        // 1. Log in as tailor
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "testtailor",
                                "password", "TestTailorPassword123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 2. Tailor can access /api/tailor/customers
        mockMvc.perform(get("/api/tailor/customers").session(session))
                .andExpect(status().isOk());

        // 3. Tailor CANNOT access /api/admin/stats (403 Forbidden)
        mockMvc.perform(get("/api/admin/stats").session(session))
                .andExpect(status().isForbidden());

        // 4. Tailor CANNOT access /api/admin/users (403 Forbidden)
        mockMvc.perform(get("/api/admin/users").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Logout invalidates the session and terminates authentication")
    void testLogoutSessionInvalidation() throws Exception {
        // 1. Log in as admin
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "testadmin",
                                "password", "TestAdminPassword123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 2. Log out
        mockMvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Logged out")));

        // 3. Confirm session is invalidated and /api/auth/me returns 401
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Public logo image is accessible without authentication")
    void testLogoAccessibleAnonymously() throws Exception {
        mockMvc.perform(get("/images/jolly-ladies-tailor-logo.png"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(
                        401, result.getResponse().getStatus(), "Logo must not return 401"));
    }

    @Test
    @DisplayName("Hardcoded backdoor admin account (admin_user) is completely removed and cannot authenticate")
    void testHardcodedAdminAccountDoesNotExistAndCannotLogin() throws Exception {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE username='admin_user'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count,
                "The hardcoded 'admin_user' backdoor account must never exist in the database");

        Map<String, String> credentials = Map.of(
                "username", "admin_user",
                "password", "AdminUserPassword123!"
        );

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(credentials)))
                .andExpect(status().isUnauthorized())
                .andExpect(status().reason("Invalid username or password"));
    }

    @Test
    @DisplayName("Last active administrator cannot be deactivated or demoted to prevent lockout")
    void testLastActiveAdministratorCannotBeDeactivatedOrDemoted() throws Exception {
        // 1. Log in as testadmin
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "testadmin",
                                "password", "TestAdminPassword123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        Long adminId = jdbc.queryForObject("SELECT id FROM app_users WHERE username='testadmin'", Long.class);
        org.junit.jupiter.api.Assertions.assertNotNull(adminId);

        // Ensure there is only 1 active admin in the test DB
        jdbc.update("DELETE FROM app_users WHERE role='ADMIN' AND id<>?", adminId);

        // 2. Attempt to deactivate the last active admin via /api/admin/users/{id}/active
        mockMvc.perform(patch("/api/admin/users/" + adminId + "/active")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("Cannot deactivate or demote the last active administrator")));

        // 3. Attempt to demote the last active admin to TAILOR via /api/admin/users/{id}/role
        mockMvc.perform(patch("/api/admin/users/" + adminId + "/role")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("role", "TAILOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("Cannot deactivate or demote the last active administrator")));
    }
}
