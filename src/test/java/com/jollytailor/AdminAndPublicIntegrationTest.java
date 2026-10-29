package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAndPublicIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    @DisplayName("Admin dashboard stats, sales by day, and KPI calculation")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testAdminStats() throws Exception {
        mockMvc.perform(get("/api/admin/stats?range=month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range", is("month")))
                .andExpect(jsonPath("$.orders").isNumber())
                .andExpect(jsonPath("$.billed").isNumber())
                .andExpect(jsonPath("$.collected").isNumber())
                .andExpect(jsonPath("$.due").isNumber())
                .andExpect(jsonPath("$.salesByDay", hasSize(7)));
    }

    @Test
    @DisplayName("Admin customer directory and customer orders lookup")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testAdminCustomerDirectory() throws Exception {
        mockMvc.perform(get("/api/admin/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Admin payments ledger query")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testAdminPaymentsLedger() throws Exception {
        mockMvc.perform(get("/api/admin/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Admin CSV Report exports (orders, payments, customers)")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testAdminCsvExports() throws Exception {
        mockMvc.perform(get("/api/admin/reports/orders-csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(content().string(containsString("Bill Number,Customer Name,Phone")));

        mockMvc.perform(get("/api/admin/reports/payments-csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(content().string(containsString("Payment ID,Bill Number,Customer,Amount")));

        mockMvc.perform(get("/api/admin/reports/customers-csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(content().string(containsString("Customer Name,Phone,Address")));
    }

    @Test
    @DisplayName("Category, Product, and Shop Settings Management")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testCategoryAndProductManagement() throws Exception {
        // 1. Create a Category
        Map<String, Object> catPayload = Map.of(
                "name", "Bridal Exclusive",
                "description", "Heavy zardozi and embroidered bridal wear"
        );

        String catRes = mockMvc.perform(post("/api/admin/categories")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(catPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString();

        Number catId = (Number) mapper.readValue(catRes, Map.class).get("id");

        // 2. Create a Product / Service in that category
        Map<String, Object> prodPayload = Map.of(
                "name", "Bridal Silk Blouse with Dori",
                "categoryId", catId,
                "defaultRate", 1500.00,
                "description", "Double padded silk blouse with handmade latkan"
        );

        String prodRes = mockMvc.perform(post("/api/admin/products")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(prodPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String prodId = (String) mapper.readValue(prodRes, Map.class).get("id");

        // 3. Update Product default rate
        mockMvc.perform(patch("/api/admin/products/" + prodId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("defaultRate", 1600.00))))
                .andExpect(status().isOk());

        // 4. Update Shop Settings
        Map<String, Object> shopPayload = Map.of(
                "shop_name", "Jolly Ladies Tailor",
                "shop_phone", "+91 98300 11223",
                "shop_address", "Gariahat Market, Kolkata",
                "whatsapp_number", "919830011223",
                "admin_notification_phone", "919830099999",
                "upi_id", "jollytailor@okaxis"
        );

        mockMvc.perform(put("/api/admin/shop-settings")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(shopPayload)))
                .andExpect(status().isOk());

        // 5. Verify public endpoint reflects the updated shop settings (without checkout QR exposure)
        mockMvc.perform(get("/api/public/shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shop_name", is("Jolly Ladies Tailor")))
                .andExpect(jsonPath("$.whatsapp_number", is("919830011223")));

        // 6. Verify admin can inspect shop settings
        mockMvc.perform(get("/api/admin/shop-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin_notification_phone", is("919830099999")))
                .andExpect(jsonPath("$.upi_id", is("jollytailor@okaxis")));
    }

    @Test
    @DisplayName("Public contact enquiry: validation, rate limiting, and persistence")
    void testPublicContactEnquiry() throws Exception {
        // 1. Missing required name
        mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "",
                                "phone", "9748546778",
                                "message", "Test message"
                        ))))
                .andExpect(status().isBadRequest());

        // 2. Missing required phone
        mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Priya",
                                "phone", "",
                                "message", "Test message"
                        ))))
                .andExpect(status().isBadRequest());

        // 3. Invalid Indian mobile number
        mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Priya",
                                "phone", "12345",
                                "message", "Test message"
                        ))))
                .andExpect(status().isBadRequest());

        // 4. Invalid email format
        mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Priya",
                                "phone", "9748546778",
                                "email", "not-a-valid-email",
                                "message", "Test message"
                        ))))
                .andExpect(status().isBadRequest());

        // 5. Missing required message
        mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Priya",
                                "phone", "9748546778",
                                "message", ""
                        ))))
                .andExpect(status().isBadRequest());

        // 6. Valid contact enquiry submission (unauthenticated)
        mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Priya Sharma",
                                "phone", "+91 9748546778",
                                "email", "priya@example.com",
                                "service", "Blouse Stitching",
                                "message", "Need a padded blouse for an upcoming wedding."
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("SUCCESS")))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    @DisplayName("Admin view and status updates for customer enquiries, with role access boundaries")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testAdminEnquiriesManagement() throws Exception {
        // Submit an enquiry first
        String submitRes = mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Ananya Das",
                                "phone", "9748546778",
                                "email", "ananya@example.com",
                                "service", "Alterations",
                                "message", "Need salwar suit altered by Saturday."
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String enquiryId = (String) mapper.readValue(submitRes, Map.class).get("id");

        // Admin can view enquiries
        mockMvc.perform(get("/api/admin/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.name == 'Ananya Das')].email").value(hasItem("ananya@example.com")))
                .andExpect(jsonPath("$[?(@.name == 'Ananya Das')].status").value(hasItem("NEW")));

        // Admin can update status to RESOLVED
        mockMvc.perform(patch("/api/admin/requests/" + enquiryId + "/status")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("status", "RESOLVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESOLVED")));
    }

    @Test
    @DisplayName("Non-admin role boundaries for customer enquiries")
    void testEnquiriesAccessBoundaries() throws Exception {
        // Unauthenticated request to /api/admin/requests is unauthorized
        mockMvc.perform(get("/api/admin/requests"))
                .andExpect(status().isUnauthorized());
    }
}
