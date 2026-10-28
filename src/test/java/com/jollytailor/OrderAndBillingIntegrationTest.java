package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
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
class OrderAndBillingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    @DisplayName("Complete Order Lifecycle: Creation, Items, Advance, Status Progression, Final Payment & Refund")
    @WithMockUser(username = "testtailor", roles = {"TAILOR"})
    void testOrderAndPaymentLifecycle() throws Exception {
        // 1. Create Customer
        String custRes = mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Sunita Sen", "phone", "9831122334"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = (String) mapper.readValue(custRes, Map.class).get("id");

        // 2. Add Measurement
        String measRes = mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "garmentType", "Salwar / Kameez",
                                "unit", "in",
                                "dimensions", Map.of("bust", "38", "waist", "32", "kameezLength", "42")
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String measurementId = (String) mapper.readValue(measRes, Map.class).get("id");

        // 3. Create Order with 2 services, discount, and advance payment
        List<Map<String, Object>> items = List.of(
                Map.of("name", "Salwar Stitching", "quantity", 1, "rate", 650.00),
                Map.of("name", "Peko & False Par", "quantity", 1, "rate", 150.00)
        );

        Map<String, Object> orderPayload = Map.of(
                "customerId", customerId,
                "measurementId", measurementId,
                "dueDate", "2026-11-15",
                "notes", "Fabric supplied by customer. Urgent delivery.",
                "discount", 50.00,
                "advanceAmount", 300.00,
                "advanceMethod", "UPI",
                "advanceRef", "UPI-TXN-12345",
                "items", items
        );

        String orderRes = mockMvc.perform(post("/api/tailor/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(orderPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order_number", startsWith("JLT-")))
                .andExpect(jsonPath("$.status", is("NEW")))
                .andExpect(jsonPath("$.payment_status", is("PARTIALLY_PAID")))
                .andExpect(jsonPath("$.subtotal", is(800.0)))
                .andExpect(jsonPath("$.discount", is(50.0)))
                .andExpect(jsonPath("$.total", is(750.0)))
                .andExpect(jsonPath("$.amount_paid", is(300.0)))
                .andExpect(jsonPath("$.due", is(450.0)))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.payments", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        String orderId = (String) mapper.readValue(orderRes, Map.class).get("id");

        // 4. Update status through exact required lifecycle: In Progress -> Trial -> Ready -> Delivered
        for (String status : List.of("IN_PROGRESS", "TRIAL", "READY", "DELIVERED")) {
            mockMvc.perform(patch("/api/tailor/orders/" + orderId + "/status")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("status", status))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is(status)));
        }

        // 5. Try overpaying (should fail with 400 Bad Request)
        mockMvc.perform(post("/api/tailor/orders/" + orderId + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 500.00, "method", "CASH"))))
                .andExpect(status().isBadRequest());

        // 6. Record remaining balance payment of 450.00
        mockMvc.perform(post("/api/tailor/orders/" + orderId + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 450.00, "method", "CASH", "reference", "Final settlement"))))
                .andExpect(status().isCreated());

        // Verify order is now fully PAID
        mockMvc.perform(get("/api/tailor/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment_status", is("PAID")))
                .andExpect(jsonPath("$.amount_paid", is(750.0)))
                .andExpect(jsonPath("$.due", is(0.0)))
                .andExpect(jsonPath("$.payments", hasSize(2)));

        // 7. Record a partial refund of 100.00
        mockMvc.perform(post("/api/tailor/orders/" + orderId + "/refunds")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 100.00, "reason", "Customer fabric discount adjustment"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentStatus", is("PARTIALLY_PAID")))
                .andExpect(jsonPath("$.newAmountPaid", is(650.0)));
    }

    @Test
    @DisplayName("Admin Pricing, Tailor Override Audit, Historical Immutability & Tailor Payment QR")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testAdminPricingAndTailorOverrides() throws Exception {
        // 1. Admin creates a service with default rate 800.00
        String prodRes = mockMvc.perform(post("/api/admin/products")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Heavy Embroidery Blouse",
                                "defaultRate", 800.00,
                                "productType", "SERVICE",
                                "description", "Hand zardozi work"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.defaultRate", is(800.0)))
                .andExpect(jsonPath("$.productType", is("SERVICE")))
                .andReturn().getResponse().getContentAsString();
        String productId = (String) mapper.readValue(prodRes, Map.class).get("id");

        // 2. Also create a ready-made stock item
        mockMvc.perform(post("/api/admin/products")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Readymade Cotton Kurti - M",
                                "defaultRate", 450.00,
                                "productType", "READY_MADE",
                                "stockQuantity", 12
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productType", is("READY_MADE")))
                .andExpect(jsonPath("$.stockQuantity", is(12)));

        // 3. Tailor creates customer and order overriding default rate (800 -> 950 with custom latkan reason)
        String custRes = mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Ananya Roy", "phone", "9830099887"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = (String) mapper.readValue(custRes, Map.class).get("id");

        String orderRes = mockMvc.perform(post("/api/tailor/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "items", List.of(Map.of(
                                        "productId", productId,
                                        "name", "Heavy Embroidery Blouse",
                                        "quantity", 1,
                                        "defaultRate", 800.00,
                                        "rate", 950.00,
                                        "adjustmentReason", "Customer requested heavy handmade silk latkan"
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total", is(950.0)))
                .andExpect(jsonPath("$.items[0].default_rate", is(800.0)))
                .andExpect(jsonPath("$.items[0].unit_rate", is(950.0)))
                .andExpect(jsonPath("$.items[0].adjustment_reason", containsString("latkan")))
                .andReturn().getResponse().getContentAsString();
        String orderId = (String) mapper.readValue(orderRes, Map.class).get("id");

        // 4. Admin inspects price adjustments audit endpoint
        mockMvc.perform(get("/api/admin/price-adjustments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].item_name", is("Heavy Embroidery Blouse")))
                .andExpect(jsonPath("$[0].default_rate", is(800.0)))
                .andExpect(jsonPath("$[0].unit_rate", is(950.0)))
                .andExpect(jsonPath("$[0].difference", is(150.0)))
                .andExpect(jsonPath("$[0].adjustment_reason", containsString("latkan")));

        // 5. Admin updates the global default rate of the product to 1000.00
        mockMvc.perform(patch("/api/admin/products/" + productId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("defaultRate", 1000.00))))
                .andExpect(status().isOk());

        // 6. Verify historical immutability: previously generated bill still has 950.00 total and 800.00 default
        mockMvc.perform(get("/api/tailor/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(950.0)))
                .andExpect(jsonPath("$.items[0].unit_rate", is(950.0)))
                .andExpect(jsonPath("$.items[0].default_rate", is(800.0)));

        // 7. Verify Tailor Workspace GPay QR endpoint
        mockMvc.perform(get("/api/tailor/payment-qr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").isNotEmpty())
                .andExpect(jsonPath("$.upiId").isNotEmpty())
                .andExpect(jsonPath("$.adminNotificationPhone").isNotEmpty());

        // 8. Verify public customer e-bill endpoint
        mockMvc.perform(get("/api/public/bills/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_number", startsWith("JLT-")))
                .andExpect(jsonPath("$.customer_name", is("Ananya Roy")))
                .andExpect(jsonPath("$.customer_phone_masked", startsWith("XXXXXX")))
                .andExpect(jsonPath("$.total", is(950.0)))
                .andExpect(jsonPath("$.items[0].item_name", is("Heavy Embroidery Blouse")))
                .andExpect(jsonPath("$.shop.name").isNotEmpty());

        // Verify phone verification on public e-bill
        mockMvc.perform(get("/api/public/bills/" + orderId + "?phone=9830099887"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/bills/" + orderId + "?phone=1111111111"))
                .andExpect(status().isForbidden());

        // 9. Verify public /api/public/shop does NOT expose internal payment QR or UPI ID as checkout feature
        mockMvc.perform(get("/api/public/shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gpay_qr_key").doesNotExist())
                .andExpect(jsonPath("$.upi_id").doesNotExist())
                .andExpect(jsonPath("$.shop_name").isNotEmpty());
    }
}
