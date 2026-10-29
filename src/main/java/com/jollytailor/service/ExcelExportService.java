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

            // 1. Customers Sheet
            buildCustomersSheet(workbook, headerStyle);

            // 2. Orders Sheet
            buildOrdersSheet(workbook, headerStyle, currencyStyle);

            // 3. Invoice Details Sheet
            buildInvoiceDetailsSheet(workbook, headerStyle, currencyStyle, intStyle);

            // 4. Payments Sheet
            buildPaymentsSheet(workbook, headerStyle, currencyStyle);

            // 5. Measurements Sheet
            buildMeasurementsSheet(workbook, headerStyle);

            // 6. Products & Inventory Sheet
            buildProductsSheet(workbook, headerStyle, currencyStyle, intStyle);

            // 7. Services & Rates Sheet
            buildServicesSheet(workbook, headerStyle, currencyStyle);

            // 8. Customer Enquiries Sheet
            buildEnquiriesSheet(workbook, headerStyle);

            // 9. Summary Sheet
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

    private void buildOrdersSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle) {
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
        }
        autoSize(sheet, headers.length);
    }

    private void buildInvoiceDetailsSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle, CellStyle intStyle) {
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
        }
        autoSize(sheet, headers.length);
    }

    private void buildPaymentsSheet(XSSFWorkbook wb, CellStyle headerStyle, CellStyle curStyle) {
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
        }
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

        int rowNum = 1;
        addSummaryRow(sheet, rowNum++, "Total Registered Customers", customerCount, intStyle, "Count of customer records");
        addSummaryRow(sheet, rowNum++, "Total Tailoring Orders", orderCount, intStyle, "Total lifetime orders");
        addSummaryRow(sheet, rowNum++, "Total Order Revenue (Gross)", totalGross, curStyle, "Sum of all order totals");
        addSummaryRow(sheet, rowNum++, "Total Amount Collected", totalPaid, curStyle, "Total payments received");
        addSummaryRow(sheet, rowNum++, "Total Outstanding Balance Due", totalDue, curStyle, "Receivables pending collection");
        addSummaryRow(sheet, rowNum++, "Total Payment Receipts", paymentsCount, intStyle, "Total transaction records");
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
        Cell cell = row.createCell(col, CellType.STRING);
        // Formula injection protection: sanitize text starting with =, +, -, @
        String sanitized = sanitize(val);
        cell.setCellValue(sanitized);
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
