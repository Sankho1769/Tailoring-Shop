package com.jollytailor.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExcelExportService {

    private final JdbcTemplate jdbc;

    public ExcelExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public byte[] generateWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            // Palette & Styles
            byte[] violetRgb = new byte[]{(byte) 64, (byte) 8, (byte) 73};
            XSSFColor headerColor = new XSSFColor(violetRgb, null);

            XSSFFont headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setFontHeightInPoints((short) 10);

            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(headerColor);
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.LEFT);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());

            DataFormat df = workbook.createDataFormat();
            CellStyle currencyStyle = workbook.createCellStyle();
            currencyStyle.setDataFormat(df.getFormat("₹#,##0.00"));

            CellStyle intStyle = workbook.createCellStyle();
            intStyle.setDataFormat(df.getFormat("#,##0"));

            XSSFFont totalFont = workbook.createFont();
            totalFont.setBold(true);
            totalFont.setFontHeightInPoints((short) 10);

            CellStyle totalTextStyle = workbook.createCellStyle();
            totalTextStyle.setFont(totalFont);
            totalTextStyle.setBorderTop(BorderStyle.THIN);
            totalTextStyle.setBorderBottom(BorderStyle.DOUBLE);

            CellStyle totalCurStyle = workbook.createCellStyle();
            totalCurStyle.setFont(totalFont);
            totalCurStyle.setDataFormat(df.getFormat("₹#,##0.00"));
            totalCurStyle.setBorderTop(BorderStyle.THIN);
            totalCurStyle.setBorderBottom(BorderStyle.DOUBLE);

            CellStyle totalIntStyle = workbook.createCellStyle();
            totalIntStyle.setFont(totalFont);
            totalIntStyle.setDataFormat(df.getFormat("#,##0"));
            totalIntStyle.setBorderTop(BorderStyle.THIN);
            totalIntStyle.setBorderBottom(BorderStyle.DOUBLE);

            // 1. Customers Sheet
            buildCustomersSheet(workbook, headerStyle);

            // 2. Orders Sheet
            buildOrdersSheet(workbook, headerStyle, currencyStyle, totalTextStyle, totalCurStyle);

            // 3. Invoice Details Sheet
            buildInvoiceDetailsSheet(workbook, headerStyle, currencyStyle, intStyle, totalTextStyle, totalCurStyle, totalIntStyle);

            // 4. Payments Sheet
            buildPaymentsSheet(workbook, headerStyle, currencyStyle, totalTextStyle, totalCurStyle);

            // 5. Measurements Sheet
            buildMeasurementsSheet(workbook, headerStyle);

            // 6. Products & Inventory Sheet
            buildProductsSheet(workbook, headerStyle, currencyStyle, intStyle);

            // 7. Categories Sheet
            buildCategoriesSheet(workbook, headerStyle);

            // 8. Services & Rates Sheet
            buildServicesSheet(workbook, headerStyle, currencyStyle);

            // 9. Staff Directory Sheet (Strictly no password hashes or credentials)
            buildStaffSheet(workbook, headerStyle, currencyStyle, totalTextStyle, totalCurStyle);

            // 10. Staff Work / Assignments Sheet
            buildStaffWorkSheet(workbook, headerStyle, currencyStyle, totalTextStyle, totalCurStyle);

            // 11. Staff Payments Sheet
            buildStaffPaymentsSheet(workbook, headerStyle, currencyStyle, totalTextStyle, totalCurStyle);

            // 12. Shop Settings Sheet
            buildShopSettingsSheet(workbook, headerStyle);

            // 13. Customer Enquiries Sheet
            buildEnquiriesSheet(workbook, headerStyle);

            // 14. Executive Summary Sheet
            buildSummarySheet(workbook, headerStyle, currencyStyle, intStyle);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    private void buildCustomersSheet(XSSFWorkbook wb, CellStyle headerStyle) {
        XSSFSheet sheet = wb.createSheet("Customers");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Customer ID", "Full Name", "Mobile Phone", "Address", "Preferences & Notes", "Registered At", "Last Updated"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, phone, address, notes, created_at, updated_at FROM customers ORDER BY created_at DESC");

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("name")));
            setText(row, 2, str(r.get("phone")));
            setText(row, 3, str(r.get("address")));
            setText(row, 4, str(r.get("notes")));
            setText(row, 5, str(r.get("created_at")));
            setText(row, 6, str(r.get("updated_at")));
        }
        autoSize(sheet, headers.length);
    }

    private void buildOrdersSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle totText, CellStyle totCur) {
        XSSFSheet sheet = wb.createSheet("Orders");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Order ID", "Order #", "Customer Name", "Customer Phone", "Order Date", "Due Date",
                "Status", "Payment Status", "Subtotal (INR)", "Discount (INR)", "Total (INR)", "Amount Paid (INR)", "Balance Due (INR)", "Instructions"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.id, o.order_number, c.name AS customer_name, c.phone AS customer_phone, o.created_at, o.due_date, " +
                "o.status, COALESCE(o.payment_status, 'PAYMENT_DUE') AS payment_status, o.subtotal, o.discount, o.total, o.amount_paid, " +
                "(o.total - o.amount_paid) AS due, o.notes " +
                "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id ORDER BY o.created_at DESC");

        BigDecimal sumSubtotal = BigDecimal.ZERO;
        BigDecimal sumDiscount = BigDecimal.ZERO;
        BigDecimal sumTotal = BigDecimal.ZERO;
        BigDecimal sumPaid = BigDecimal.ZERO;
        BigDecimal sumDue = BigDecimal.ZERO;

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("order_number")));
            setText(row, 2, str(r.get("customer_name")));
            setText(row, 3, str(r.get("customer_phone")));
            setText(row, 4, str(r.get("created_at")));
            setText(row, 5, str(r.get("due_date")));
            setText(row, 6, str(r.get("status")));
            setText(row, 7, str(r.get("payment_status")));
            setDecimal(row, 8, r.get("subtotal"), curStyle);
            setDecimal(row, 9, r.get("discount"), curStyle);
            setDecimal(row, 10, r.get("total"), curStyle);
            setDecimal(row, 11, r.get("amount_paid"), curStyle);
            setDecimal(row, 12, r.get("due"), curStyle);
            setText(row, 13, str(r.get("notes")));

            sumSubtotal = sumSubtotal.add(dec(r.get("subtotal")));
            sumDiscount = sumDiscount.add(dec(r.get("discount")));
            sumTotal = sumTotal.add(dec(r.get("total")));
            sumPaid = sumPaid.add(dec(r.get("amount_paid")));
            sumDue = sumDue.add(dec(r.get("due")));
        }

        // Distinct double-underlined Totals Row
        Row totRow = sheet.createRow(rowNum++);
        setText(totRow, 0, "TOTAL (" + rows.size() + " orders)", totText);
        for (int i = 1; i <= 7; i++) setText(totRow, i, "", totText);
        setDecimal(totRow, 8, sumSubtotal, totCur);
        setDecimal(totRow, 9, sumDiscount, totCur);
        setDecimal(totRow, 10, sumTotal, totCur);
        setDecimal(totRow, 11, sumPaid, totCur);
        setDecimal(totRow, 12, sumDue, totCur);
        setText(totRow, 13, "", totText);

        autoSize(sheet, headers.length);
    }

    private void buildInvoiceDetailsSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle intStyle, CellStyle totText, CellStyle totCur, CellStyle totInt) {
        XSSFSheet sheet = wb.createSheet("Invoice Details");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Order ID", "Order #", "Customer Name", "Item / Service", "Qty", "Default Rate (INR)", "Unit Rate (INR)", "Discount (INR)", "Line Total (INR)", "Adjustment Reason"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT oi.order_id, o.order_number, c.name AS customer_name, oi.item_name, oi.quantity, oi.default_rate, oi.unit_rate, oi.discount, oi.amount, oi.adjustment_reason " +
                "FROM order_items oi " +
                "JOIN tailoring_orders o ON o.id=oi.order_id " +
                "JOIN customers c ON c.id=o.customer_id " +
                "ORDER BY o.created_at DESC, oi.id");

        long sumQty = 0;
        BigDecimal sumAmount = BigDecimal.ZERO;

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("order_id")));
            setText(row, 1, str(r.get("order_number")));
            setText(row, 2, str(r.get("customer_name")));
            setText(row, 3, str(r.get("item_name")));
            setLong(row, 4, r.get("quantity"), intStyle);
            setDecimal(row, 5, r.get("default_rate"), curStyle);
            setDecimal(row, 6, r.get("unit_rate"), curStyle);
            setDecimal(row, 7, r.get("discount"), curStyle);
            setDecimal(row, 8, r.get("amount"), curStyle);
            setText(row, 9, str(r.get("adjustment_reason")));

            if (r.get("quantity") != null) {
                try { sumQty += Long.parseLong(r.get("quantity").toString()); } catch (Exception ignored) {}
            }
            sumAmount = sumAmount.add(dec(r.get("amount")));
        }

        // Totals Row
        Row totRow = sheet.createRow(rowNum++);
        setText(totRow, 0, "TOTAL (" + rows.size() + " items)", totText);
        for (int i = 1; i <= 3; i++) setText(totRow, i, "", totText);
        setLong(totRow, 4, sumQty, totInt);
        setText(totRow, 5, "", totText);
        setText(totRow, 6, "", totText);
        setText(totRow, 7, "", totText);
        setDecimal(totRow, 8, sumAmount, totCur);
        setText(totRow, 9, "", totText);

        autoSize(sheet, headers.length);
    }

    private void buildPaymentsSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle totText, CellStyle totCur) {
        XSSFSheet sheet = wb.createSheet("Payments");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Payment ID", "Order #", "Customer Name", "Amount (INR)", "Method", "Type", "Reference", "Note", "Received Date", "Collector"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.id, o.order_number, c.name AS customer_name, p.amount, p.method, COALESCE(p.payment_type, 'PAYMENT') AS payment_type, " +
                "p.reference, p.note, p.received_at, u.username AS collector " +
                "FROM payments p " +
                "JOIN tailoring_orders o ON o.id=p.order_id " +
                "JOIN customers c ON c.id=o.customer_id " +
                "LEFT JOIN app_users u ON u.id=p.received_by " +
                "ORDER BY p.received_at DESC");

        BigDecimal sumPay = BigDecimal.ZERO;

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("order_number")));
            setText(row, 2, str(r.get("customer_name")));
            setDecimal(row, 3, r.get("amount"), curStyle);
            setText(row, 4, str(r.get("method")));
            setText(row, 5, str(r.get("payment_type")));
            setText(row, 6, str(r.get("reference")));
            setText(row, 7, str(r.get("note")));
            setText(row, 8, str(r.get("received_at")));
            setText(row, 9, str(r.get("collector")));

            sumPay = sumPay.add(dec(r.get("amount")));
        }

        // Totals Row
        Row totRow = sheet.createRow(rowNum++);
        setText(totRow, 0, "TOTAL (" + rows.size() + " payments)", totText);
        setText(totRow, 1, "", totText);
        setText(totRow, 2, "", totText);
        setDecimal(totRow, 3, sumPay, totCur);
        for (int i = 4; i <= 9; i++) setText(totRow, i, "", totText);

        autoSize(sheet, headers.length);
    }

    private void buildMeasurementsSheet(XSSFWorkbook wb, CellStyle headerStyle) {
        XSSFSheet sheet = wb.createSheet("Measurements");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Measurement ID", "Customer Name", "Customer Phone", "Garment Type", "Unit", "Fit Preference", "Notes", "Recorded At", "Dimensions"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.id, c.name AS customer_name, c.phone AS customer_phone, m.garment_type, m.unit, m.fit_preference, m.notes, m.created_at, CAST(m.dimensions AS VARCHAR) AS dimensions " +
                "FROM measurements m JOIN customers c ON c.id=m.customer_id ORDER BY m.created_at DESC");

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("customer_name")));
            setText(row, 2, str(r.get("customer_phone")));
            setText(row, 3, str(r.get("garment_type")));
            setText(row, 4, str(r.get("unit")));
            setText(row, 5, str(r.get("fit_preference")));
            setText(row, 6, str(r.get("notes")));
            setText(row, 7, str(r.get("created_at")));
            setText(row, 8, str(r.get("dimensions")));
        }
        autoSize(sheet, headers.length);
    }

    private void buildProductsSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle intStyle) {
        XSSFSheet sheet = wb.createSheet("Products & Inventory");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Product ID", "Product Name", "Category", "Type", "Default Rate (INR)", "Stock Quantity", "Active"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.id, p.name, c.name AS category, p.product_type, p.default_rate, p.stock_quantity, p.active " +
                "FROM products p LEFT JOIN categories c ON c.id=p.category_id ORDER BY c.name, p.name");

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("name")));
            setText(row, 2, str(r.get("category")));
            setText(row, 3, str(r.get("product_type")));
            setDecimal(row, 4, r.get("default_rate"), curStyle);
            setLong(row, 5, r.get("stock_quantity"), intStyle);
            setText(row, 6, Boolean.TRUE.equals(r.get("active")) ? "YES" : "NO");
        }
        autoSize(sheet, headers.length);
    }

    private void buildCategoriesSheet(XSSFWorkbook wb, CellStyle headerStyle) {
        XSSFSheet sheet = wb.createSheet("Categories");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Category ID", "Category Name", "Description", "Active", "Created At"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, description, active, created_at FROM categories ORDER BY id");

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("name")));
            setText(row, 2, str(r.get("description")));
            setText(row, 3, Boolean.TRUE.equals(r.get("active")) ? "YES" : "NO");
            setText(row, 4, str(r.get("created_at")));
        }
        autoSize(sheet, headers.length);
    }

    private void buildServicesSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle) {
        XSSFSheet sheet = wb.createSheet("Services & Rates");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Service Name", "Category", "Default Rate (INR)", "Description", "Active"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.name, c.name AS category, p.default_rate, p.description, p.active " +
                "FROM products p LEFT JOIN categories c ON c.id=p.category_id WHERE p.product_type='SERVICE' ORDER BY c.name, p.name");

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("name")));
            setText(row, 1, str(r.get("category")));
            setDecimal(row, 2, r.get("default_rate"), curStyle);
            setText(row, 3, str(r.get("description")));
            setText(row, 4, Boolean.TRUE.equals(r.get("active")) ? "YES" : "NO");
        }
        autoSize(sheet, headers.length);
    }

    private void buildStaffSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle totText, CellStyle totCur) {
        XSSFSheet sheet = wb.createSheet("Staff Directory");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Staff ID", "Username", "Full Name", "Role", "Active Status",
                "Total Earned (INR)", "Total Paid (INR)", "Remaining Balance (INR)", "Created Date", "Last Login"};
        createHeaderRow(sheet, headers, headerStyle);

        // Explicitly excludes password_hash or credentials from select
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.username, u.full_name, u.role, u.active, u.created_at, u.last_login_at, " +
                "COALESCE(w.total_earned, 0) AS total_earned, " +
                "COALESCE(p.total_paid, 0) AS total_paid, " +
                "(COALESCE(w.total_earned, 0) - COALESCE(p.total_paid, 0)) AS remaining_balance " +
                "FROM app_users u " +
                "LEFT JOIN (SELECT staff_id, SUM(amount_owed) AS total_earned FROM staff_work GROUP BY staff_id) w ON w.staff_id = u.id " +
                "LEFT JOIN (SELECT staff_id, SUM(amount) AS total_paid FROM staff_payments GROUP BY staff_id) p ON p.staff_id = u.id " +
                "ORDER BY u.id");

        BigDecimal sumEarned = BigDecimal.ZERO;
        BigDecimal sumPaid = BigDecimal.ZERO;
        BigDecimal sumBal = BigDecimal.ZERO;

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("username")));
            setText(row, 2, str(r.get("full_name")));
            setText(row, 3, str(r.get("role")));
            setText(row, 4, Boolean.TRUE.equals(r.get("active")) ? "ACTIVE" : "DISABLED");
            setDecimal(row, 5, r.get("total_earned"), curStyle);
            setDecimal(row, 6, r.get("total_paid"), curStyle);
            setDecimal(row, 7, r.get("remaining_balance"), curStyle);
            setText(row, 8, str(r.get("created_at")));
            setText(row, 9, str(r.get("last_login_at")));

            sumEarned = sumEarned.add(dec(r.get("total_earned")));
            sumPaid = sumPaid.add(dec(r.get("total_paid")));
            sumBal = sumBal.add(dec(r.get("remaining_balance")));
        }

        // Totals Row
        Row totRow = sheet.createRow(rowNum++);
        setText(totRow, 0, "TOTAL (" + rows.size() + " staff)", totText);
        for (int i = 1; i <= 4; i++) setText(totRow, i, "", totText);
        setDecimal(totRow, 5, sumEarned, totCur);
        setDecimal(totRow, 6, sumPaid, totCur);
        setDecimal(totRow, 7, sumBal, totCur);
        setText(totRow, 8, "", totText);
        setText(totRow, 9, "", totText);

        autoSize(sheet, headers.length);
    }

    private void buildStaffWorkSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle totText, CellStyle totCur) {
        XSSFSheet sheet = wb.createSheet("Staff Work");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Work ID", "Staff Member", "Staff Username", "Order #", "Work Date",
                "Description / Work", "Amount Owed (INR)", "Notes", "Recorded Date", "Recorded By"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = List.of();
        try {
            rows = jdbc.queryForList(
                    "SELECT w.id, u.full_name AS staff_name, u.username AS staff_user, o.order_number, " +
                    "w.work_date, w.description, w.amount_owed, w.notes, w.created_at, " +
                    "rec.username AS recorded_by_user " +
                    "FROM staff_work w " +
                    "JOIN app_users u ON u.id = w.staff_id " +
                    "LEFT JOIN tailoring_orders o ON o.id = w.order_id " +
                    "LEFT JOIN app_users rec ON rec.id = w.recorded_by " +
                    "ORDER BY w.work_date DESC, w.created_at DESC");
        } catch (Exception ignored) {}

        BigDecimal sumWork = BigDecimal.ZERO;

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("staff_name")));
            setText(row, 2, str(r.get("staff_user")));
            setText(row, 3, str(r.get("order_number")));
            setText(row, 4, str(r.get("work_date")));
            setText(row, 5, str(r.get("description")));
            setDecimal(row, 6, r.get("amount_owed"), curStyle);
            setText(row, 7, str(r.get("notes")));
            setText(row, 8, str(r.get("created_at")));
            setText(row, 9, str(r.get("recorded_by_user")));

            sumWork = sumWork.add(dec(r.get("amount_owed")));
        }

        // Totals Row
        Row totRow = sheet.createRow(rowNum++);
        setText(totRow, 0, "TOTAL (" + rows.size() + " assignments)", totText);
        for (int i = 1; i <= 5; i++) setText(totRow, i, "", totText);
        setDecimal(totRow, 6, sumWork, totCur);
        for (int i = 7; i <= 9; i++) setText(totRow, i, "", totText);

        autoSize(sheet, headers.length);
    }

    private void buildStaffPaymentsSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle totText, CellStyle totCur) {
        XSSFSheet sheet = wb.createSheet("Staff Payments");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Payment ID", "Staff Member", "Staff Username", "Payment Date",
                "Amount Paid (INR)", "Payment Method", "Reference", "Notes", "Recorded Date", "Recorded By"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = List.of();
        try {
            rows = jdbc.queryForList(
                    "SELECT p.id, u.full_name AS staff_name, u.username AS staff_user, " +
                    "p.payment_date, p.amount, p.payment_method, p.reference, p.notes, p.created_at, " +
                    "rec.username AS recorded_by_user " +
                    "FROM staff_payments p " +
                    "JOIN app_users u ON u.id = p.staff_id " +
                    "LEFT JOIN app_users rec ON rec.id = p.recorded_by " +
                    "ORDER BY p.payment_date DESC, p.created_at DESC");
        } catch (Exception ignored) {}

        BigDecimal sumPaid = BigDecimal.ZERO;

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("staff_name")));
            setText(row, 2, str(r.get("staff_user")));
            setText(row, 3, str(r.get("payment_date")));
            setDecimal(row, 4, r.get("amount"), curStyle);
            setText(row, 5, str(r.get("payment_method")));
            setText(row, 6, str(r.get("reference")));
            setText(row, 7, str(r.get("notes")));
            setText(row, 8, str(r.get("created_at")));
            setText(row, 9, str(r.get("recorded_by_user")));

            sumPaid = sumPaid.add(dec(r.get("amount")));
        }

        // Totals Row
        Row totRow = sheet.createRow(rowNum++);
        setText(totRow, 0, "TOTAL (" + rows.size() + " disbursements)", totText);
        for (int i = 1; i <= 3; i++) setText(totRow, i, "", totText);
        setDecimal(totRow, 4, sumPaid, totCur);
        for (int i = 5; i <= 9; i++) setText(totRow, i, "", totText);

        autoSize(sheet, headers.length);
    }

    private void buildShopSettingsSheet(XSSFWorkbook wb, CellStyle headerStyle) {
        XSSFSheet sheet = wb.createSheet("Shop Settings");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Setting Key", "Setting Value", "Last Updated"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT setting_key, setting_value, updated_at FROM shop_settings " +
                "WHERE setting_key NOT LIKE '%secret%' AND setting_key NOT LIKE '%key%' AND setting_key NOT LIKE '%password%' " +
                "ORDER BY setting_key");

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("setting_key")));
            setText(row, 1, str(r.get("setting_value")));
            setText(row, 2, str(r.get("updated_at")));
        }
        autoSize(sheet, headers.length);
    }

    private void buildEnquiriesSheet(XSSFWorkbook wb, CellStyle headerStyle) {
        XSSFSheet sheet = wb.createSheet("Customer Enquiries");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Enquiry ID", "Customer Name", "Phone", "Email", "Service Requested", "Message", "Status", "Submitted At"};
        createHeaderRow(sheet, headers, headerStyle);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, phone, email, requested_service, message, status, created_at FROM customer_requests ORDER BY created_at DESC");

        int rowNum = 1;
        for (Map<String, Object> r : rows) {
            Row row = sheet.createRow(rowNum++);
            setText(row, 0, str(r.get("id")));
            setText(row, 1, str(r.get("name")));
            setText(row, 2, str(r.get("phone")));
            setText(row, 3, str(r.get("email")));
            setText(row, 4, str(r.get("requested_service")));
            setText(row, 5, str(r.get("message")));
            setText(row, 6, str(r.get("status")));
            setText(row, 7, str(r.get("created_at")));
        }
        autoSize(sheet, headers.length);
    }

    private void buildSummarySheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle intStyle) {
        XSSFSheet sheet = wb.createSheet("Summary");
        sheet.createFreezePane(0, 1);

        String[] headers = {"Metric / KPI", "Value", "Notes"};
        createHeaderRow(sheet, headers, headerStyle);

        // Calculate actual KPI values from database
        Long customerCount = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class);
        Long orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM tailoring_orders", Long.class);
        BigDecimal totalGross = jdbc.queryForObject("SELECT COALESCE(SUM(total), 0) FROM tailoring_orders", BigDecimal.class);
        BigDecimal totalPaid = jdbc.queryForObject("SELECT COALESCE(SUM(amount_paid), 0) FROM tailoring_orders", BigDecimal.class);
        BigDecimal totalDue = jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(total - amount_paid, 0)), 0) FROM tailoring_orders", BigDecimal.class);
        Long paymentsCount = jdbc.queryForObject("SELECT COUNT(*) FROM payments", Long.class);
        Long productsCount = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE active=true", Long.class);
        Long enquiriesCount = jdbc.queryForObject("SELECT COUNT(*) FROM customer_requests", Long.class);
        Long pendingEnquiries = jdbc.queryForObject("SELECT COUNT(*) FROM customer_requests WHERE status='NEW'", Long.class);

        Long activeStaffCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE active=true", Long.class);
        BigDecimal totalStaffWork = BigDecimal.ZERO;
        BigDecimal totalStaffPaid = BigDecimal.ZERO;
        try {
            totalStaffWork = jdbc.queryForObject("SELECT COALESCE(SUM(amount_owed), 0) FROM staff_work", BigDecimal.class);
            totalStaffPaid = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM staff_payments", BigDecimal.class);
        } catch (Exception ignored) {}
        BigDecimal totalStaffBalance = (totalStaffWork != null ? totalStaffWork : BigDecimal.ZERO)
                .subtract(totalStaffPaid != null ? totalStaffPaid : BigDecimal.ZERO);

        int rowNum = 1;
        addSummaryRow(sheet, rowNum++, "Total Registered Customers", customerCount, intStyle, "Count of customer records");
        addSummaryRow(sheet, rowNum++, "Total Tailoring Orders", orderCount, intStyle, "Total lifetime orders");
        addSummaryRow(sheet, rowNum++, "Total Order Revenue (Gross)", totalGross, curStyle, "Sum of all order totals");
        addSummaryRow(sheet, rowNum++, "Total Amount Collected", totalPaid, curStyle, "Total payments received");
        addSummaryRow(sheet, rowNum++, "Total Outstanding Balance Due", totalDue, curStyle, "Receivables pending collection");
        addSummaryRow(sheet, rowNum++, "Total Payment Receipts", paymentsCount, intStyle, "Total transaction records");
        addSummaryRow(sheet, rowNum++, "Active Staff Members", activeStaffCount, intStyle, "Active employee logins");
        addSummaryRow(sheet, rowNum++, "Total Staff Work Value Logged", totalStaffWork, curStyle, "Sum of all recorded staff work");
        addSummaryRow(sheet, rowNum++, "Total Staff Payments Disbursed", totalStaffPaid, curStyle, "Total actual payments paid to staff");
        addSummaryRow(sheet, rowNum++, "Remaining Staff Balance Owed", totalStaffBalance, curStyle, "Total unpaid work balance across staff");
        addSummaryRow(sheet, rowNum++, "Active Products & Services", productsCount, intStyle, "Current catalog count");
        addSummaryRow(sheet, rowNum++, "Total Customer Enquiries", enquiriesCount, intStyle, "Total public website enquiries");
        addSummaryRow(sheet, rowNum++, "Pending Enquiries (Action Required)", pendingEnquiries, intStyle, "Enquiries in NEW status");

        Row exportInfoRow = sheet.createRow(rowNum++);
        setText(exportInfoRow, 0, "Export Generated At");
        setText(exportInfoRow, 1, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        setText(exportInfoRow, 2, "Generated by Jolly Ladies Tailor Admin Console");

        autoSize(sheet, headers.length);
    }

    private void addSummaryRow(XSSFSheet sheet, int rowNum, String metric, Object val, CellStyle style, String notes) {
        Row row = sheet.createRow(rowNum);
        setText(row, 0, metric);
        if (val instanceof BigDecimal bd) {
            setDecimal(row, 1, bd, style);
        } else if (val instanceof Number n) {
            setLong(row, 1, n.longValue(), style);
        } else {
            setText(row, 1, str(val));
        }
        setText(row, 2, notes);
    }

    private void createHeaderRow(XSSFSheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(24);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private void setText(Row row, int col, String val) {
        setText(row, col, val, null);
    }

    private void setText(Row row, int col, String val, CellStyle style) {
        Cell cell = row.createCell(col, CellType.STRING);
        // Formula injection protection: sanitize text starting with =, +, -, @
        String sanitized = sanitize(val);
        cell.setCellValue(sanitized);
        if (style != null) cell.setCellStyle(style);
    }

    private BigDecimal dec(Object val) {
        if (val == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(val.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private void setDecimal(Row row, int col, Object val, CellStyle style) {
        Cell cell = row.createCell(col, CellType.NUMERIC);
        if (val == null) {
            cell.setCellValue(0.0);
        } else {
            try {
                cell.setCellValue(new BigDecimal(val.toString()).doubleValue());
            } catch (Exception e) {
                cell.setCellValue(0.0);
            }
        }
        if (style != null) cell.setCellStyle(style);
    }

    private void setLong(Row row, int col, Object val, CellStyle style) {
        Cell cell = row.createCell(col, CellType.NUMERIC);
        if (val == null) {
            cell.setCellValue(0);
        } else {
            try {
                cell.setCellValue(Long.parseLong(val.toString()));
            } catch (Exception e) {
                cell.setCellValue(0);
            }
        }
        if (style != null) cell.setCellStyle(style);
    }

    private String sanitize(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@")) {
            return "'" + trimmed;
        }
        return s;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private void autoSize(XSSFSheet sheet, int numCols) {
        for (int i = 0; i < numCols; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            // Minimum readable width and maximum cap to avoid runaway widths on long notes
            if (width < 3200) sheet.setColumnWidth(i, 3200);
            if (width > 18000) sheet.setColumnWidth(i, 18000);
        }
    }
}
