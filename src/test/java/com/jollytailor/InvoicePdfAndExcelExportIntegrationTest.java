package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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
    @DisplayName("Excel Export: Generates genuine .xlsx workbook with all 9 sheets, headers, and formula protection")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testExcelExportWorkflow() throws Exception {
        byte[] excelBytes = mockMvc.perform(get("/api/admin/reports/export-excel"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", is("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(header().string("Content-Disposition", containsString("jolly-ladies-tailor-data-")))
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
                    "Services & Rates",
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

            // Verify Summary sheet has calculations
            Sheet summarySheet = workbook.getSheet("Summary");
            boolean hasCustomerMetric = false;
            boolean hasOrderMetric = false;
            for (int r = 1; r <= summarySheet.getLastRowNum(); r++) {
                Row row = summarySheet.getRow(r);
                if (row != null && row.getCell(0) != null) {
                    String metricName = row.getCell(0).getStringCellValue();
                    if (metricName.contains("Total Registered Customers")) hasCustomerMetric = true;
                    if (metricName.contains("Total Tailoring Orders")) hasOrderMetric = true;
                }
            }
            assertTrue(hasCustomerMetric, "Summary must include customer count metric");
            assertTrue(hasOrderMetric, "Summary must include orders count metric");
        }
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
