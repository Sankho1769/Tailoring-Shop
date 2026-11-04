package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
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
class AdminTailorInterconnectionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Admin creates a customer and order -> Tailor workspace sees both in shared database")
    @WithMockUser(username = "testadmin", roles = {"ADMIN"})
    void testAdminCreatesCustomerAndOrder_TailorSeesThem() throws Exception {
        String testPhone = "98765" + (10000 + new Random().nextInt(89999));
        Map<String, Object> customerReq = Map.of(
                "name", "Ananya Sen",
                "phone", testPhone,
                "address", "45 Southern Avenue, Kolkata",
                "notes", "VIP client, prefer silk materials"
        );

        // 1. Admin creates customer via /api/admin/customers
        MvcResult custResult = mockMvc.perform(post("/api/admin/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(customerReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name", is("Ananya Sen")))
                .andReturn();

        Map<?, ?> createdCust = mapper.readValue(custResult.getResponse().getContentAsString(), Map.class);
        String customerId = (String) createdCust.get("id");

        // 2. Admin creates a measurement profile for this customer
        Map<String, Object> measureReq = Map.of(
                "garmentType", "Blouse",
                "unit", "in",
                "dimensions", Map.of("chest", 36, "waist", 30, "length", 15),
                "fitPreference", "Slim Fit",
                "notes", "Boat neck design"
        );
        MvcResult measureResult = mockMvc.perform(post("/api/admin/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(measureReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();

        Map<?, ?> createdMeasure = mapper.readValue(measureResult.getResponse().getContentAsString(), Map.class);
        String measurementId = (String) createdMeasure.get("id");

        // 3. Admin creates an order for this customer
        Map<String, Object> orderReq = Map.of(
                "customerId", customerId,
                "measurementId", measurementId,
                "items", List.of(
                        Map.of("name", "Designer Blouse Stitching", "quantity", 1, "unitRate", 1500)
                ),
                "notes", "Promised for festival season"
        );
        MvcResult orderResult = mockMvc.perform(post("/api/admin/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(orderReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.order_number").exists())
                .andExpect(jsonPath("$.total", is(1500.0)))
                .andReturn();

        Map<?, ?> createdOrder = mapper.readValue(orderResult.getResponse().getContentAsString(), Map.class);
        String orderId = (String) createdOrder.get("id");
        String orderNumber = (String) createdOrder.get("order_number");

        // 4. Now verify from Tailor perspective (switch role to TAILOR)
        // Tailor searches customers by phone and finds Ananya Sen
        mockMvc.perform(get("/api/tailor/customers?q=" + testPhone)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].name", is("Ananya Sen")));

        // Tailor views customer's measurements and finds the blouse measurement
        mockMvc.perform(get("/api/tailor/customers/" + customerId + "/measurements")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].garment_type", is("Blouse")));

        // Tailor views orders and finds the order created by Admin
        mockMvc.perform(get("/api/tailor/orders/" + orderId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_number", is(orderNumber)))
                .andExpect(jsonPath("$.total", is(1500.0)))
                .andExpect(jsonPath("$.status", is("NEW")));
    }

    @Test
    @DisplayName("Tailor updates order status & records payment -> Admin panel sees update in shared database")
    void testTailorUpdatesOrderAndRecordsPayment_AdminSeesUpdate() throws Exception {
        // Create customer and order first
        String testPhone = "98765" + (10000 + new Random().nextInt(89999));
        UUID custId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, name, phone, address) VALUES(?,?,?,?)",
                custId, "Meera Mukherjee", testPhone, "Gariahat, Kolkata");

        UUID ordId = UUID.randomUUID();
        String orderNumber = "JLT-TEST-" + System.currentTimeMillis();
        jdbc.update("INSERT INTO tailoring_orders(id, customer_id, order_number, status, subtotal, total, amount_paid, payment_status) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                ordId, custId, orderNumber, "NEW", 2000, 2000, 0, "PAYMENT_DUE");

        jdbc.update("INSERT INTO order_items(id, order_id, item_name, quantity, default_rate, unit_rate, amount) " +
                    "VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID(), ordId, "Salwar Suit Stitching", 1, 2000, 2000, 2000);

        // 1. Tailor updates order status to 'READY'
        mockMvc.perform(patch("/api/tailor/orders/" + ordId + "/status")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("status", "READY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("READY")));

        // 2. Tailor records a payment of Rs. 1200 via UPI
        mockMvc.perform(post("/api/tailor/orders/" + ordId + "/payments")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "amount", 1200,
                                "method", "UPI",
                                "reference", "UPI-REF-998877",
                                "note", "Advance payment received by tailor"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount", is(1200.0)))
                .andExpect(jsonPath("$.order.due", is(800.0)));

        // 3. Now verify from Admin perspective (role ADMIN)
        // Admin queries order details via /api/admin/orders/{id}
        mockMvc.perform(get("/api/admin/orders/" + ordId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("READY")))
                .andExpect(jsonPath("$.amount_paid", is(1200.0)))
                .andExpect(jsonPath("$.due", is(800.0)))
                .andExpect(jsonPath("$.payment_status", is("PARTIALLY_PAID")));

        // Admin queries payment ledger via /api/admin/payments
        mockMvc.perform(get("/api/admin/payments?q=UPI-REF-998877")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].reference", is("UPI-REF-998877")))
                .andExpect(jsonPath("$[0].amount", is(1200.0)))
                .andExpect(jsonPath("$[0].customer", is("Meera Mukherjee")));
    }

    @Test
    @DisplayName("Admin updates catalog service rates -> Tailor workspace sees updated rates")
    void testAdminUpdatesCatalogRate_TailorSeesUpdate() throws Exception {
        // Query an existing active product or insert one
        UUID prodId = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id, name, default_rate, product_type, stock_quantity, active) " +
                    "VALUES(?,?,?,?,?,?)", prodId, "Custom Silk Lehenga", 8000, "SERVICE", 0, true);

        // Admin updates rate to 9500
        mockMvc.perform(patch("/api/admin/products/" + prodId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testadmin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("defaultRate", 9500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Product updated")));

        // Tailor fetches public products for order configuration
        mockMvc.perform(get("/api/public/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + prodId + "')].default_rate", contains(9500.0)));
    }

    @Test
    @DisplayName("Admin records staff work and payments; Tailor is blocked with 403 Forbidden")
    void testAdminRecordsStaffWorkAndPayments_TailorBlocked() throws Exception {
        // Find tailor1 id from app_users
        Long tailorId = jdbc.queryForObject("SELECT id FROM app_users WHERE username='testtailor'", Long.class);

        // Admin records work for tailor
        Map<String, Object> workReq = Map.of(
                "workDate", "2026-11-04",
                "amountOwed", 450,
                "description", "Blouse cutting and handwork embroidery",
                "notes", "Completed ahead of schedule"
        );
        mockMvc.perform(post("/api/admin/staff/" + tailorId + "/work")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testadmin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(workReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountOwed", notNullValue()));

        // Admin records payment to tailor
        Map<String, Object> payReq = Map.of(
                "paymentDate", "2026-11-04",
                "amount", 450,
                "paymentMethod", "CASH",
                "reference", "CASH-STAFF-101",
                "notes", "Settled daily wages"
        );
        mockMvc.perform(post("/api/admin/staff/" + tailorId + "/payments")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testadmin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount", notNullValue()));

        // Tailor attempts to access staff management -> 403 Forbidden
        mockMvc.perform(get("/api/admin/staff")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR")))
                .andExpect(status().isForbidden());

        // Tailor attempts to record staff work -> 403 Forbidden
        mockMvc.perform(post("/api/admin/staff/" + tailorId + "/work")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(workReq)))
                .andExpect(status().isForbidden());
    }
}
