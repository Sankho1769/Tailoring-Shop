package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class StaffWorkAndHisabIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("Staff Work & Hisab: Create work entry with Pay Now, record later payments, and verify balances")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testStaffWorkAndHisabCompleteLifecycle() throws Exception {
        // 1. Create a dedicated tailor staff member
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String staffRes = mockMvc.perform(post("/api/admin/staff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "hisab_tailor_" + suffix,
                                "password", "SecureTailorPassword123!",
                                "role", "TAILOR",
                                "fullName", "Master Tailor " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> createdStaff = mapper.readValue(staffRes, Map.class);
        Long staffId = ((Number) createdStaff.get("id")).longValue();

        // 2. Check initial hisab: 0 earned, 0 paid, 0 remaining
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(0)))
                .andExpect(jsonPath("$.totalPaid", is(0)))
                .andExpect(jsonPath("$.remainingBalance", is(0)))
                .andExpect(jsonPath("$.workHistory", hasSize(0)));

        // 3. Admin records work: "Stitched 3 blouses" with Total Cost 900.00 and Pay Now 300.00
        Map<String, Object> workPayload1 = Map.of(
                "workDate", LocalDate.now().toString(),
                "description", "Stitched 3 blouses",
                "totalCost", new BigDecimal("900.00"),
                "payNow", new BigDecimal("300.00"),
                "paymentMethod", "CASH",
                "notes", "Fabric supplied by client"
        );

        String workRes1 = mockMvc.perform(post("/api/admin/staff/" + staffId + "/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(workPayload1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.description", is("Stitched 3 blouses")))
                .andExpect(jsonPath("$.totalCost", is(900.00)))
                .andExpect(jsonPath("$.amountPaid", is(300.00)))
                .andExpect(jsonPath("$.remainingBalance", is(600.00)))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> createdWork1 = mapper.readValue(workRes1, Map.class);
        String workId1 = (String) createdWork1.get("id");

        // 4. Verify hisab ledger: Total Earned = 900, Total Paid = 300, Remaining = 600
        // Entry 1 status should be PARTIAL with remaining_balance = 600
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(900.00)))
                .andExpect(jsonPath("$.totalPaid", is(300.00)))
                .andExpect(jsonPath("$.remainingBalance", is(600.00)))
                .andExpect(jsonPath("$.workHistory", hasSize(1)))
                .andExpect(jsonPath("$.workHistory[0].description", is("Stitched 3 blouses")))
                .andExpect(jsonPath("$.workHistory[0].total_cost", is(900.00)))
                .andExpect(jsonPath("$.workHistory[0].total_paid", is(300.00)))
                .andExpect(jsonPath("$.workHistory[0].remaining_balance", is(600.00)))
                .andExpect(jsonPath("$.workHistory[0].status", is("PARTIAL")))
                .andExpect(jsonPath("$.workHistory[0].payments", hasSize(1)));

        // 5. Admin records a later partial payment against Work Entry 1: Rs. 200 via UPI
        Map<String, Object> laterPay1 = Map.of(
                "amount", new BigDecimal("200.00"),
                "paymentDate", LocalDate.now().toString(),
                "paymentMethod", "UPI",
                "reference", "GPAY-998877",
                "notes", "Second installment for 3 blouses"
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/work/" + workId1 + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(laterPay1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount", is(200.00)))
                .andExpect(jsonPath("$.remainingBalance", is(400.00)));

        // 6. Verify Work Entry 1 now has 2 payments, total paid = 500, remaining = 400
        // And previous payment history was completely preserved!
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(900.00)))
                .andExpect(jsonPath("$.totalPaid", is(500.00)))
                .andExpect(jsonPath("$.remainingBalance", is(400.00)))
                .andExpect(jsonPath("$.workHistory[0].total_paid", is(500.00)))
                .andExpect(jsonPath("$.workHistory[0].remaining_balance", is(400.00)))
                .andExpect(jsonPath("$.workHistory[0].payments", hasSize(2)))
                .andExpect(jsonPath("$.paymentHistory", hasSize(2)));

        // 7. Validation: Payment exceeding the remaining balance (400) must be rejected with 400 Bad Request
        Map<String, Object> overPay = Map.of(
                "amount", new BigDecimal("450.00"),
                "paymentMethod", "CASH"
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/work/" + workId1 + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(overPay)))
                .andExpect(status().isBadRequest());

        // 8. Admin records final payment of exact remaining balance (Rs. 400)
        Map<String, Object> finalPay = Map.of(
                "amount", new BigDecimal("400.00"),
                "paymentMethod", "CASH",
                "notes", "Final settlement for 3 blouses"
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/work/" + workId1 + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(finalPay)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingBalance", is(0.00)));

        // 9. Verify Entry 1 is now marked PAID with remaining balance = 0
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(900.00)))
                .andExpect(jsonPath("$.totalPaid", is(900.00)))
                .andExpect(jsonPath("$.remainingBalance", is(0.00)))
                .andExpect(jsonPath("$.workHistory[0].total_paid", is(900.00)))
                .andExpect(jsonPath("$.workHistory[0].remaining_balance", is(0.00)))
                .andExpect(jsonPath("$.workHistory[0].status", is("PAID")))
                .andExpect(jsonPath("$.workHistory[0].payments", hasSize(3)))
                .andExpect(jsonPath("$.paymentHistory", hasSize(3)));

        // 10. Record a second work entry: "Altered 2 salwar suits" with Total Cost 350.00, Pay Now 0 (unpaid)
        Map<String, Object> workPayload2 = Map.of(
                "workDate", LocalDate.now().toString(),
                "description", "Altered 2 salwar suits",
                "totalCost", new BigDecimal("350.00"),
                "payNow", BigDecimal.ZERO
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(workPayload2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCost", is(350.00)))
                .andExpect(jsonPath("$.amountPaid", is(0)))
                .andExpect(jsonPath("$.remainingBalance", is(350.00)));

        // Verify totals across both entries: Total Earned = 1250, Total Paid = 900, Remaining = 350
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(1250.00)))
                .andExpect(jsonPath("$.totalPaid", is(900.00)))
                .andExpect(jsonPath("$.remainingBalance", is(350.00)))
                .andExpect(jsonPath("$.workHistory", hasSize(2)));
    }

    @Test
    @DisplayName("Validation: Negative cost or Pay Now exceeding total cost must be rejected")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testStaffWorkValidationRules() throws Exception {
        // Negative cost rejected
        mockMvc.perform(post("/api/admin/staff/2/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "description", "Invalid work",
                                "totalCost", new BigDecimal("-200.00")
                        ))))
                .andExpect(status().isBadRequest());

        // Pay Now exceeding total cost rejected
        mockMvc.perform(post("/api/admin/staff/2/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "description", "Excessive pay now",
                                "totalCost", new BigDecimal("500.00"),
                                "payNow", new BigDecimal("600.00")
                        ))))
                .andExpect(status().isBadRequest());

        // Negative Pay Now rejected
        mockMvc.perform(post("/api/admin/staff/2/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "description", "Negative pay now",
                                "totalCost", new BigDecimal("500.00"),
                                "payNow", new BigDecimal("-50.00")
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Security Boundary: Non-admin roles and unauthenticated requests are strictly forbidden")
    void testStaffHisabSecurityBoundaries() throws Exception {
        // Unauthenticated access rejected
        mockMvc.perform(get("/api/admin/staff/2/ledger"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/staff/2/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test\",\"totalCost\":100}"))
                .andExpect(status().isUnauthorized());

        // TAILOR role rejected
        mockMvc.perform(get("/api/admin/staff/2/ledger")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tailor_user").roles("TAILOR")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/staff/2/work")
                        .with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("tailor_user").roles("TAILOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test\",\"totalCost\":100}"))
                .andExpect(status().isForbidden());
    }
}
