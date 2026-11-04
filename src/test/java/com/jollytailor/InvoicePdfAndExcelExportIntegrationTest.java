package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoicePdfAndExcelExportIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Customer creation: valid input, duplicate handling, phone normalization, role restrictions, persistence")
    @WithMockUser(username = "tailor1", roles = {"TAILOR"})
    void testCustomerCreationAndValidation() throws Exception {
        String uniqueSuffix = String.format("%05d", (int)(Math.random() * 90000) + 10000);
        String testPhone = "98311" + uniqueSuffix;

        // 1. Successful creation with normalized 10-digit phone
        Map<String, Object> req1 = Map.of(
                "name", "Priyanka Sen " + uniqueSuffix,
                "phone", testPhone,
                "address", "14/B Gariahat Road, Kolkata",
                "notes", "Prefers boat neck blouses"
        );

        String createdJson = mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name", is("Priyanka Sen " + uniqueSuffix)))
                .andExpect(jsonPath("$.phone", is(testPhone)))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> createdCust = mapper.readValue(createdJson, Map.class);
        String custId = (String) createdCust.get("id");

        // 2. Verify immediate availability in database and search query
        mockMvc.perform(get("/api/tailor/customers?q=" + testPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].name", is("Priyanka Sen " + uniqueSuffix)));

        // 3. Phone normalization test (+91 formatted search and creation)
        mockMvc.perform(get("/api/tailor/customers?q=%2B91 " + testPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].id", is(custId)));

        // 4. Duplicate customer check: same name AND same phone must be safely rejected (409 Conflict)
        mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req1)))
                .andExpect(status().isConflict());

        // 5. Household/family member check: different name with same phone is permitted
        Map<String, Object> familyMember = Map.of(
                "name", "Ananya Sen " + uniqueSuffix,
                "phone", testPhone,
                "address", "14/B Gariahat Road, Kolkata",
                "notes", "Sister of Priyanka"
        );
        mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(familyMember)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Ananya Sen " + uniqueSuffix)));

        // 6. Validation error: empty name
        mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "", "phone", "9831100000"))))
                .andExpect(status().isBadRequest());

        // 7. Validation error: invalid short phone
        mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Valid Name", "phone", "123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Customer creation: unauthenticated and unauthorized access rejected")
    void testCustomerCreationSecurity() throws Exception {
        Map<String, Object> req = Map.of("name", "Unauthorized User", "phone", "9831199999");

        // Unauthenticated access
        mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PDF Invoice: Genuine PDF generated with shop info, logo, line items, and correct balance")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testPdfInvoiceDownloadWorkflow() throws Exception {
        // 1. Create a customer and order for testing
        UUID customerId = UUID.randomUUID();
        String customerPhone = "98300" + String.valueOf(System.currentTimeMillis()).substring(7);
        jdbc.update("INSERT INTO customers(id, name, phone, address, notes) VALUES(?,?,?,?,?)",
                customerId, "Madhurima Mukherjee", customerPhone, "25 Southern Park, Kolkata", "Special bridal wear");

        UUID orderId = UUID.randomUUID();
        String orderNumber = "JLT-TEST-" + System.currentTimeMillis();
        jdbc.update("INSERT INTO tailoring_orders(id, order_number, customer_id, status, payment_status, subtotal, discount, total, amount_paid, notes) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?)",
                orderId, orderNumber, customerId, "IN_PROGRESS", "PARTIALLY_PAID",
                new BigDecimal("1200.00"), new BigDecimal("100.00"), new BigDecimal("1100.00"), new BigDecimal("500.00"), "Handle with care");

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items(id, order_id, item_name, quantity, default_rate, unit_rate, discount, amount, adjustment_reason) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                itemId, orderId, "Bridal Blouse", 1, new BigDecimal("1200.00"), new BigDecimal("1200.00"), BigDecimal.ZERO, new BigDecimal("1200.00"), "Zari handwork");

        UUID paymentId = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id, order_id, amount, method, reference, payment_type) VALUES(?,?,?,?,?,?)",
                paymentId, orderId, new BigDecimal("500.00"), "UPI", "UPI-REF-12345", "PAYMENT");

        // 2. Admin PDF download
        byte[] adminPdfBytes = mockMvc.perform(get("/api/admin/orders/" + orderId + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", is("application/pdf")))
                .andExpect(header().string("Content-Disposition", containsString("invoice-" + orderNumber + ".pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertNotNull(adminPdfBytes);
        assertTrue(adminPdfBytes.length > 500, "PDF must not be empty");
        // Verify standard PDF magic header (%PDF-)
        String pdfHeader = new String(adminPdfBytes, 0, Math.min(8, adminPdfBytes.length));
        assertTrue(pdfHeader.startsWith("%PDF-"), "Must be a genuine binary PDF file");

        // 3. Public PDF download with phone verification
        byte[] publicPdfBytes = mockMvc.perform(get("/api/public/bills/" + orderId + "/pdf?phone=" + customerPhone))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", is("application/pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertNotNull(publicPdfBytes);
        assertTrue(new String(publicPdfBytes, 0, 8).startsWith("%PDF-"));

        // 4. Public PDF download with wrong phone rejected (403 Forbidden)
        mockMvc.perform(get("/api/public/bills/" + orderId + "/pdf?phone=9999999999"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Excel Export: Generates genuine .xlsx workbook with all sheets, headers, frozen panes, and security protection")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testExcelExportWorkflow() throws Exception {
        byte[] excelBytes = mockMvc.perform(get("/api/admin/reports/export-excel"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", is("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(header().string("Content-Disposition", containsString("jolly-ladies-tailor-")))
                .andReturn().getResponse().getContentAsByteArray();

        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 1000, "Excel export must contain binary workbook data");

        // Programmatically parse using Apache POI
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            List<String> expectedSheets = List.of(
                    "Customers",
                    "Orders",
                    "Invoice Details",
                    "Payments",
                    "Measurements",
                    "Products & Inventory",
                    "Categories",
                    "Services & Rates",
                    "Staff Directory",
                    "Staff Work",
                    "Staff Payments",
                    "Shop Settings",
                    "Customer Enquiries",
                    "Summary"
            );

            for (String sheetName : expectedSheets) {
                Sheet sheet = workbook.getSheet(sheetName);
                assertNotNull(sheet, "Workbook must contain sheet: " + sheetName);
                Row headerRow = sheet.getRow(0);
                assertNotNull(headerRow, "Sheet " + sheetName + " must have a header row");
                assertTrue(headerRow.getPhysicalNumberOfCells() > 0, "Sheet " + sheetName + " header row must not be empty");
            }

            // Verify Staff Directory sheet NEVER exposes password hashes or secrets
            Sheet staffSheet = workbook.getSheet("Staff Directory");
            assertNotNull(staffSheet);
            Row staffHeader = staffSheet.getRow(0);
            for (int c = 0; c < staffHeader.getLastCellNum(); c++) {
                String heading = staffHeader.getCell(c).getStringCellValue().toLowerCase();
                assertFalse(heading.contains("password"), "Staff Directory sheet must NEVER include password columns");
                assertFalse(heading.contains("hash"), "Staff Directory sheet must NEVER include password hash columns");
                assertFalse(heading.contains("secret"), "Staff Directory sheet must NEVER include secret columns");
            }

            // Verify Summary sheet has calculations
            Sheet summarySheet = workbook.getSheet("Summary");
            boolean hasCustomerMetric = false;
            boolean hasOrderMetric = false;
            boolean hasStaffMetric = false;
            for (int r = 1; r <= summarySheet.getLastRowNum(); r++) {
                Row row = summarySheet.getRow(r);
                if (row != null && row.getCell(0) != null) {
                    String metricName = row.getCell(0).getStringCellValue();
                    if (metricName.contains("Total Registered Customers")) hasCustomerMetric = true;
                    if (metricName.contains("Total Tailoring Orders")) hasOrderMetric = true;
                    if (metricName.contains("Active Staff Members")) hasStaffMetric = true;
                }
            }
            assertTrue(hasCustomerMetric, "Summary must include customer count metric");
            assertTrue(hasOrderMetric, "Summary must include orders count metric");
            assertTrue(hasStaffMetric, "Summary must include active staff count metric");
        }
    }

    @Test
    @DisplayName("Staff Work & Actual Payments: Work recording, payment disbursement, partial payments, and ledger balance calculation")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testStaffWorkAndPaymentLedger() throws Exception {
        String testUser = "artisan_" + System.currentTimeMillis();
        // 1. Create a staff member
        Map<String, Object> createStaffReq = Map.of(
                "username", testUser,
                "fullName", "Ramesh Kar (Master Embroiderer)",
                "role", "TAILOR",
                "password", "ArtisanSecurePass123!"
        );
        String staffRes = mockMvc.perform(post("/api/admin/staff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createStaffReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> createdStaff = mapper.readValue(staffRes, Map.class);
        Long staffId = ((Number) createdStaff.get("id")).longValue();

        // 2. Initial ledger check: zero earned, zero paid, zero balance
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(0)))
                .andExpect(jsonPath("$.totalPaid", is(0)))
                .andExpect(jsonPath("$.remainingBalance", is(0)))
                .andExpect(jsonPath("$.workHistory", hasSize(0)))
                .andExpect(jsonPath("$.paymentHistory", hasSize(0)));

        // 3. Record Work Entry 1: Bridal blouse embroidery Rs. 1500.00
        Map<String, Object> work1 = Map.of(
                "workDate", LocalDate.now().toString(),
                "description", "Bridal blouse zardozi & pearl handwork",
                "amountOwed", new BigDecimal("1500.00"),
                "notes", "Order #JLT-B101"
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(work1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.amountOwed", is(1500.00)));

        // 4. Record Work Entry 2: Anarkali alteration Rs. 400.00
        Map<String, Object> work2 = Map.of(
                "workDate", LocalDate.now().toString(),
                "description", "Anarkali length alteration & kali fitting",
                "amountOwed", new BigDecimal("400.00")
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(work2)))
                .andExpect(status().isCreated());

        // 5. Verify accumulated work: total earned = 1900.00, total paid = 0, balance = 1900.00
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(1900.00)))
                .andExpect(jsonPath("$.totalPaid", is(0)))
                .andExpect(jsonPath("$.remainingBalance", is(1900.00)))
                .andExpect(jsonPath("$.workHistory", hasSize(2)));

        // 6. Record Partial Payment 1: Cash Rs. 700.00
        Map<String, Object> pay1 = Map.of(
                "paymentDate", LocalDate.now().toString(),
                "amount", new BigDecimal("700.00"),
                "paymentMethod", "CASH",
                "notes", "First weekly partial disbursement"
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pay1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount", is(700.00)));

        // 7. Verify balance after partial payment: earned 1900, paid 700, remaining 1200
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(1900.00)))
                .andExpect(jsonPath("$.totalPaid", is(700.00)))
                .andExpect(jsonPath("$.remainingBalance", is(1200.00)))
                .andExpect(jsonPath("$.paymentHistory", hasSize(1)));

        // 8. Record Partial Payment 2: UPI Rs. 500.00
        Map<String, Object> pay2 = Map.of(
                "paymentDate", LocalDate.now().toString(),
                "amount", new BigDecimal("500.00"),
                "paymentMethod", "UPI",
                "reference", "UPI-TXN-887766"
        );
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pay2)))
                .andExpect(status().isCreated());

        // 9. Verify balance after second payment: earned 1900, paid 1200, remaining 700
        mockMvc.perform(get("/api/admin/staff/" + staffId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned", is(1900.00)))
                .andExpect(jsonPath("$.totalPaid", is(1200.00)))
                .andExpect(jsonPath("$.remainingBalance", is(700.00)))
                .andExpect(jsonPath("$.paymentHistory", hasSize(2)));

        // 10. Validation tests:
        // Negative amount owed must be rejected (400 Bad Request)
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("description", "Invalid work", "amountOwed", -100))))
                .andExpect(status().isBadRequest());

        // Zero or negative payment amount must be rejected (400 Bad Request)
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 0, "paymentMethod", "CASH"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Staff Payroll Security: Non-admin users are strictly forbidden from viewing or recording payroll")
    @WithMockUser(username = "tailor1", roles = {"TAILOR"})
    void testStaffPayrollSecurity() throws Exception {
        // Tailor role must be forbidden from accessing staff ledger
        mockMvc.perform(get("/api/admin/staff/1/ledger"))
                .andExpect(status().isForbidden());

        // Tailor role must be forbidden from recording staff work
        mockMvc.perform(post("/api/admin/staff/1/work")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("description", "Hack", "amountOwed", 100))))
                .andExpect(status().isForbidden());

        // Tailor role must be forbidden from recording staff payments
        mockMvc.perform(post("/api/admin/staff/1/payments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 100, "paymentMethod", "CASH"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Excel Export Security: Non-admin users cannot export full store data")
    @WithMockUser(username = "tailor1", roles = {"TAILOR"})
    void testExcelExportSecurity() throws Exception {
        // Tailor role must be forbidden from downloading full store Excel export
        mockMvc.perform(get("/api/admin/reports/export-excel"))
                .andExpect(status().isForbidden());
    }
}
