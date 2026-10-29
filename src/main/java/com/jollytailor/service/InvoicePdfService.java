package com.jollytailor.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvoicePdfService {

    private final JdbcTemplate jdbc;
    private static final Color BRAND_VIOLET = new Color(64, 8, 73);
    private static final Color BRAND_ORCHID = new Color(231, 176, 222);
    private static final Color BRAND_SOFT = new Color(253, 246, 252);
    private static final Color TEXT_DARK = new Color(37, 21, 47);
    private static final Color TEXT_MUTED = new Color(120, 107, 122);
    private static final Color BORDER_COLOR = new Color(232, 220, 233);
    private static final Color SUCCESS_GREEN = new Color(24, 100, 61);
    private static final Color DUE_RED = new Color(179, 0, 0);

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter IST_DATETIME_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy, hh:mm a 'IST'", Locale.ENGLISH);
    private static final DateTimeFormatter IST_DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    public InvoicePdfService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public byte[] generateInvoicePdf(UUID orderId) {
        Map<String, Object> order;
        try {
            order = jdbc.queryForMap(
                "SELECT o.id, o.order_number, o.customer_id, o.status, " +
                "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                "o.due_date, o.notes, o.subtotal, o.discount, o.total, o.amount_paid, (o.total - o.amount_paid) AS due, o.created_at, " +
                "c.name AS customer_name, c.phone AS customer_phone, c.address AS customer_address, c.notes AS customer_notes " +
                "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id WHERE o.id=?", orderId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT item_name, quantity, default_rate, unit_rate, discount, amount, adjustment_reason " +
                "FROM order_items WHERE order_id=? ORDER BY id", orderId);

        List<Map<String, Object>> payments = jdbc.queryForList(
                "SELECT amount, method, reference, note, COALESCE(payment_type, 'PAYMENT') AS payment_type, received_at " +
                "FROM payments WHERE order_id=? ORDER BY received_at", orderId);

        Map<String, String> shopSettings = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM shop_settings", rs -> {
            shopSettings.put(rs.getString(1), rs.getString(2));
        });

        String shopName = shopSettings.getOrDefault("shop_name", "Jolly Ladies Tailor");
        String shopAddress = shopSettings.getOrDefault("shop_address", "12/A Southern Avenue, Kolkata - 700029");
        String shopPhone = shopSettings.getOrDefault("shop_phone", "9748546778");
        String shopWhatsApp = shopSettings.getOrDefault("whatsapp_number", "919748546778");

        // Authoritative Customer Debt Calculation: Separate current order balance vs historical previous orders
        UUID customerId = (UUID) order.get("customer_id");
        BigDecimal previousOrdersDue = jdbc.queryForObject(
                "SELECT COALESCE(SUM(GREATEST(total - amount_paid, 0)), 0) FROM tailoring_orders " +
                "WHERE customer_id = ? AND id <> ? AND status <> 'CANCELLED'",
                BigDecimal.class, customerId, orderId);
        if (previousOrdersDue == null || previousOrdersDue.signum() < 0) {
            previousOrdersDue = BigDecimal.ZERO;
        }

        BigDecimal currentInvoiceDue = decimal(order.get("due"));
        if (currentInvoiceDue.signum() < 0) {
            currentInvoiceDue = BigDecimal.ZERO;
        }
        BigDecimal overallCustomerDebt = currentInvoiceDue.add(previousOrdersDue);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);

        try {
            PdfWriter.getInstance(document, baos);
            document.open();

            // Document Metadata
            document.addTitle("Tax Invoice - " + order.get("order_number"));
            document.addAuthor(shopName);
            document.addSubject("Tailoring Stitching Invoice");

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, BRAND_VIOLET);
            Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 10, TEXT_MUTED);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
            Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 9, TEXT_DARK);
            Font boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, TEXT_DARK);
            Font smallMuted = FontFactory.getFont(FontFactory.HELVETICA, 8, TEXT_MUTED);

            // 1. Header with Official Sewing Machine Logo Asset
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{1.2f, 3.8f});
            headerTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            Image logoImg = null;
            try (InputStream in = getClass().getResourceAsStream("/static/images/jolly-ladies-tailor-logo.png")) {
                if (in != null) {
                    byte[] bytes = in.readAllBytes();
                    logoImg = Image.getInstance(bytes);
                    logoImg.scaleToFit(65, 65);
                }
            } catch (Exception ignored) {
            }

            PdfPCell logoCell = new PdfPCell();
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            if (logoImg != null) {
                logoCell.addElement(logoImg);
            }
            headerTable.addCell(logoCell);

            PdfPCell shopInfoCell = new PdfPCell();
            shopInfoCell.setBorder(Rectangle.NO_BORDER);
            shopInfoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph pShopName = new Paragraph(shopName, titleFont);
            Paragraph pShopSubtitle = new Paragraph("Custom Ladies Tailoring & Alteration Specialist · Made to Fit You", subtitleFont);
            Paragraph pShopContact = new Paragraph(
                    shopAddress + "\nPhone: " + shopPhone + "  ·  WhatsApp: +" + shopWhatsApp, smallMuted);
            shopInfoCell.addElement(pShopName);
            shopInfoCell.addElement(pShopSubtitle);
            shopInfoCell.addElement(pShopContact);
            headerTable.addCell(shopInfoCell);

            document.add(headerTable);
            document.add(new Paragraph(" "));

            // 2. Invoice Banner with IST Issue Timestamp
            PdfPTable bannerTable = new PdfPTable(2);
            bannerTable.setWidthPercentage(100);
            bannerTable.setWidths(new float[]{2.5f, 2.5f});

            PdfPCell bannerLeft = new PdfPCell();
            bannerLeft.setBackgroundColor(BRAND_SOFT);
            bannerLeft.setBorderColor(BRAND_ORCHID);
            bannerLeft.setPadding(8);
            bannerLeft.addElement(new Paragraph("INVOICE / BILL: " + order.get("order_number"), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BRAND_VIOLET)));
            bannerLeft.addElement(new Paragraph("Order Status: " + order.get("status") + "  ·  Payment: " + order.get("payment_status"), boldFont));
            bannerTable.addCell(bannerLeft);

            PdfPCell bannerRight = new PdfPCell();
            bannerRight.setBackgroundColor(BRAND_SOFT);
            bannerRight.setBorderColor(BRAND_ORCHID);
            bannerRight.setPadding(8);
            bannerRight.setHorizontalAlignment(Element.ALIGN_RIGHT);
            String orderDateTimeStr = formatDateTimeIST(order.get("created_at"));
            String dueDateStr = order.get("due_date") != null ? formatDateOnly(order.get("due_date")) : "As agreed";
            Paragraph pDates = new Paragraph("Invoice Date & Time (IST):\n" + orderDateTimeStr + "\nPromised Delivery: " + dueDateStr, normalFont);
            pDates.setAlignment(Element.ALIGN_RIGHT);
            bannerRight.addElement(pDates);
            bannerTable.addCell(bannerRight);

            document.add(bannerTable);
            document.add(new Paragraph(" "));

            // 3. Customer Info Section
            PdfPTable custTable = new PdfPTable(1);
            custTable.setWidthPercentage(100);
            PdfPCell custCell = new PdfPCell();
            custCell.setBorderColor(BORDER_COLOR);
            custCell.setBackgroundColor(Color.WHITE);
            custCell.setPadding(8);
            custCell.addElement(new Paragraph("BILL TO (CUSTOMER):", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, BRAND_VIOLET)));
            custCell.addElement(new Paragraph(String.valueOf(order.get("customer_name")), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, TEXT_DARK)));
            custCell.addElement(new Paragraph("Phone: " + order.get("customer_phone"), normalFont));
            if (order.get("customer_address") != null && !String.valueOf(order.get("customer_address")).isBlank()) {
                custCell.addElement(new Paragraph("Address: " + order.get("customer_address"), normalFont));
            }
            if (order.get("notes") != null && !String.valueOf(order.get("notes")).isBlank()) {
                custCell.addElement(new Paragraph("Special Instructions: " + order.get("notes"), smallMuted));
            }
            custTable.addCell(custCell);
            document.add(custTable);
            document.add(new Paragraph(" "));

            // 4. Line Items Table
            PdfPTable itemsTable = new PdfPTable(4);
            itemsTable.setWidthPercentage(100);
            itemsTable.setWidths(new float[]{4.0f, 1.0f, 1.5f, 1.5f});

            addHeaderCell(itemsTable, "Service / Item Description", headerFont, Element.ALIGN_LEFT);
            addHeaderCell(itemsTable, "Qty", headerFont, Element.ALIGN_CENTER);
            addHeaderCell(itemsTable, "Unit Rate (INR)", headerFont, Element.ALIGN_RIGHT);
            addHeaderCell(itemsTable, "Amount (INR)", headerFont, Element.ALIGN_RIGHT);

            boolean alt = false;
            for (Map<String, Object> item : items) {
                Color rowBg = alt ? BRAND_SOFT : Color.WHITE;
                alt = !alt;

                String itemName = String.valueOf(item.get("item_name"));
                String adjReason = item.get("adjustment_reason") != null ? String.valueOf(item.get("adjustment_reason")) : null;
                if (adjReason != null && !adjReason.isBlank()) {
                    itemName += "\n  (Note: " + adjReason + ")";
                }

                addBodyCell(itemsTable, itemName, normalFont, Element.ALIGN_LEFT, rowBg);
                addBodyCell(itemsTable, String.valueOf(item.get("quantity")), normalFont, Element.ALIGN_CENTER, rowBg);
                addBodyCell(itemsTable, formatCurrency(item.get("unit_rate")), normalFont, Element.ALIGN_RIGHT, rowBg);
                addBodyCell(itemsTable, formatCurrency(item.get("amount")), boldFont, Element.ALIGN_RIGHT, rowBg);
            }
            document.add(itemsTable);

            // 5. Totals & Balance Table for THIS Order
            PdfPTable totalsTable = new PdfPTable(2);
            totalsTable.setWidthPercentage(100);
            totalsTable.setWidths(new float[]{3.0f, 2.0f});

            PdfPCell termsCell = new PdfPCell();
            termsCell.setBorder(Rectangle.NO_BORDER);
            termsCell.setPadding(8);
            termsCell.addElement(new Paragraph("Terms & Fitting Guarantee:", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, BRAND_VIOLET)));
            termsCell.addElement(new Paragraph(
                    "✓ 100% Custom fitting guarantee with trial adjustment included.\n" +
                    "✓ Please present this invoice at the time of final fitting and delivery.\n" +
                    "✓ Unclaimed garments retained for up to 90 days from due date.", smallMuted));
            totalsTable.addCell(termsCell);

            PdfPTable amountsTable = new PdfPTable(2);
            amountsTable.setWidthPercentage(100);
            amountsTable.setWidths(new float[]{1.5f, 1.5f});

            addTotalRow(amountsTable, "Subtotal:", formatCurrency(order.get("subtotal")), normalFont);
            BigDecimal discount = decimal(order.get("discount"));
            if (discount.signum() > 0) {
                addTotalRow(amountsTable, "Discount:", "- " + formatCurrency(discount), normalFont);
            }
            addTotalRow(amountsTable, "Total Amount for this Order:", formatCurrency(order.get("total")), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, BRAND_VIOLET));
            addTotalRow(amountsTable, "Amount Paid:", formatCurrency(order.get("amount_paid")), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, SUCCESS_GREEN));
            addTotalRow(amountsTable, "Balance Due (this Invoice):", formatCurrency(currentInvoiceDue), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, DUE_RED));

            PdfPCell amountsContainer = new PdfPCell(amountsTable);
            amountsContainer.setBorderColor(BORDER_COLOR);
            amountsContainer.setPadding(0);
            totalsTable.addCell(amountsContainer);

            document.add(totalsTable);

            // 6. Dedicated Customer Account Balance Statement (Separates Current Due vs Previous Orders Debt)
            document.add(new Paragraph(" "));
            PdfPTable acctTable = new PdfPTable(2);
            acctTable.setWidthPercentage(100);
            acctTable.setWidths(new float[]{3.2f, 1.8f});

            PdfPCell acctHeader = new PdfPCell(new Phrase("Customer Account Balance Statement", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, BRAND_VIOLET)));
            acctHeader.setColspan(2);
            acctHeader.setBackgroundColor(BRAND_SOFT);
            acctHeader.setBorderColor(BRAND_ORCHID);
            acctHeader.setPadding(6);
            acctTable.addCell(acctHeader);

            addAcctRow(acctTable, "Current Invoice Balance Due:", formatCurrency(currentInvoiceDue), normalFont);
            addAcctRow(acctTable, "Previous Orders Outstanding Balance:", formatCurrency(previousOrdersDue), normalFont);
            addAcctRow(acctTable, "Total Customer Outstanding Balance:", formatCurrency(overallCustomerDebt),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, previousOrdersDue.signum() > 0 ? DUE_RED : BRAND_VIOLET));

            PdfPCell noteCell = new PdfPCell(new Phrase("* Note: Outstanding balance from previous open orders is shown for customer account reconciliation only. It is kept separate and not added to this order's invoice total.", smallMuted));
            noteCell.setColspan(2);
            noteCell.setBorderColor(BORDER_COLOR);
            noteCell.setPadding(5);
            noteCell.setBackgroundColor(Color.WHITE);
            acctTable.addCell(noteCell);

            document.add(acctTable);

            // 7. Payment History (with exact IST timestamps)
            if (!payments.isEmpty()) {
                document.add(new Paragraph(" "));
                Paragraph pPayHeader = new Paragraph("Payment Receipts Recorded on this Order", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, BRAND_VIOLET));
                document.add(pPayHeader);

                PdfPTable payTable = new PdfPTable(4);
                payTable.setWidthPercentage(100);
                payTable.setWidths(new float[]{2.2f, 1.4f, 2.4f, 1.8f});
                addHeaderCell(payTable, "Date & Time (IST)", headerFont, Element.ALIGN_LEFT);
                addHeaderCell(payTable, "Type / Method", headerFont, Element.ALIGN_LEFT);
                addHeaderCell(payTable, "Reference / Note", headerFont, Element.ALIGN_LEFT);
                addHeaderCell(payTable, "Amount (INR)", headerFont, Element.ALIGN_RIGHT);

                for (Map<String, Object> p : payments) {
                    String pDate = formatDateTimeIST(p.get("received_at"));
                    String method = p.get("payment_type") + " (" + p.get("method") + ")";
                    String ref = (p.get("reference") != null ? p.get("reference") : "") +
                                 (p.get("note") != null ? " " + p.get("note") : "");
                    addBodyCell(payTable, pDate, smallMuted, Element.ALIGN_LEFT, Color.WHITE);
                    addBodyCell(payTable, method, normalFont, Element.ALIGN_LEFT, Color.WHITE);
                    addBodyCell(payTable, ref.trim(), smallMuted, Element.ALIGN_LEFT, Color.WHITE);
                    addBodyCell(payTable, formatCurrency(p.get("amount")), boldFont, Element.ALIGN_RIGHT, Color.WHITE);
                }
                document.add(payTable);
            }

            // 8. Footer
            document.add(new Paragraph(" "));
            Paragraph footer = new Paragraph(
                    "Thank you for your patronage! Stitched with precision at " + shopName + ".\n" +
                    "For enquiries or trial appointments, contact us at " + shopPhone + " or via WhatsApp at +" + shopWhatsApp + ".",
                    smallMuted);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to render PDF invoice: " + e.getMessage());
        }
    }

    private void addHeaderCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(BRAND_VIOLET);
        cell.setHorizontalAlignment(align);
        cell.setPadding(6);
        cell.setBorderColor(BRAND_VIOLET);
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text, Font font, int align, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(align);
        cell.setPadding(6);
        cell.setBorderColor(BORDER_COLOR);
        table.addCell(cell);
    }

    private void addTotalRow(PdfPTable table, String label, String value, Font font) {
        PdfPCell c1 = new PdfPCell(new Phrase(label, font));
        c1.setBorderColor(BORDER_COLOR);
        c1.setPadding(4);
        PdfPCell c2 = new PdfPCell(new Phrase(value, font));
        c2.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c2.setBorderColor(BORDER_COLOR);
        c2.setPadding(4);
        table.addCell(c1);
        table.addCell(c2);
    }

    private void addAcctRow(PdfPTable table, String label, String value, Font font) {
        PdfPCell c1 = new PdfPCell(new Phrase(label, font));
        c1.setBorderColor(BORDER_COLOR);
        c1.setBackgroundColor(Color.WHITE);
        c1.setPadding(5);
        PdfPCell c2 = new PdfPCell(new Phrase(value, font));
        c2.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c2.setBorderColor(BORDER_COLOR);
        c2.setBackgroundColor(Color.WHITE);
        c2.setPadding(5);
        table.addCell(c1);
        table.addCell(c2);
    }

    private String formatCurrency(Object val) {
        if (val == null) return "Rs. 0.00";
        try {
            BigDecimal bd = new BigDecimal(val.toString());
            return "Rs. " + String.format(Locale.ENGLISH, "%,.2f", bd);
        } catch (Exception e) {
            return "Rs. " + val;
        }
    }

    private String formatDateTimeIST(Object val) {
        if (val == null) return "";
        try {
            if (val instanceof java.sql.Timestamp ts) {
                return ts.toInstant().atZone(IST_ZONE).format(IST_DATETIME_FMT);
            }
            if (val instanceof OffsetDateTime odt) {
                return odt.atZoneSameInstant(IST_ZONE).format(IST_DATETIME_FMT);
            }
            if (val instanceof Instant inst) {
                return inst.atZone(IST_ZONE).format(IST_DATETIME_FMT);
            }
            String str = val.toString();
            try {
                OffsetDateTime parsed = OffsetDateTime.parse(str);
                return parsed.atZoneSameInstant(IST_ZONE).format(IST_DATETIME_FMT);
            } catch (Exception ex) {
                if (str.length() >= 10) {
                    LocalDate ld = LocalDate.parse(str.substring(0, 10));
                    return ld.format(IST_DATE_FMT);
                }
            }
            return str;
        } catch (Exception e) {
            return String.valueOf(val);
        }
    }

    private String formatDateOnly(Object val) {
        if (val == null) return "";
        try {
            String str = val.toString();
            if (str.length() >= 10) {
                LocalDate ld = LocalDate.parse(str.substring(0, 10));
                return ld.format(IST_DATE_FMT);
            }
            return str;
        } catch (Exception e) {
            return String.valueOf(val);
        }
    }

    private BigDecimal decimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(val.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
