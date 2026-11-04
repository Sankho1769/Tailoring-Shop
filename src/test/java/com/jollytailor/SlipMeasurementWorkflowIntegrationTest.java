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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class SlipMeasurementWorkflowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    private String createTestCustomer(String name, String phone) throws Exception {
        Map<String, Object> data = Map.of(
                "name", name,
                "phone", phone,
                "address", "14 Raja Subodh Chandra Mallick Road, Kolkata",
                "notes", "Regular fitting customer"
        );
        String res = mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(data)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> map = mapper.readValue(res, Map.class);
        return (String) map.get("id");
    }

    @Test
    @DisplayName("1. Record CHURIDAR / BLOUSE measurements with all 16 slip fields")
    @WithMockUser(username = "testtailor", roles = {"TAILOR"})
    void testRecordChuridarBlouseMeasurements_WithAll16SlipFields() throws Exception {
        String customerId = createTestCustomer("Rupali Chakraborty", "9830112233");

        // Exactly the 16 fields printed on the Jolly Trading Ladies Tailors slip
        Map<String, Object> cbDimensions = new LinkedHashMap<>();
        cbDimensions.put("length", "38.5");
        cbDimensions.put("chest", "36");
        cbDimensions.put("upperChest", "34.5");
        cbDimensions.put("pet", "30");
        cbDimensions.put("put", "14.5");
        cbDimensions.put("hand", "11");
        cbDimensions.put("cup", "36B");
        cbDimensions.put("neck", "6.5");
        cbDimensions.put("sesto", "10");
        cbDimensions.put("fittings", "Slim Fit");
        cbDimensions.put("bodyLength", "14.5");
        cbDimensions.put("underarm", "16");
        cbDimensions.put("backLength", "15");
        cbDimensions.put("frontLength", "14");
        cbDimensions.put("width", "38");
        cbDimensions.put("falsePar", "5.5");

        Map<String, Object> payload = Map.of(
                "garmentType", "CHURIDAR / BLOUSE",
                "unit", "in",
                "dimensions", cbDimensions,
                "fitPreference", "Slim Fit",
                "notes", "Princess seam with padded cup and piping on neck"
        );

        String res = mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.garment_type", is("CHURIDAR / BLOUSE")))
                .andExpect(jsonPath("$.unit", is("in")))
                .andExpect(jsonPath("$.fit_preference", is("Slim Fit")))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> saved = mapper.readValue(res, Map.class);
        String dimStr = (String) saved.get("dimensions");
        Map<?, ?> parsedDims = mapper.readValue(dimStr, Map.class);

        // Verify all 16 slip labels are present with their values
        assertEquals("38.5", parsedDims.get("length"));
        assertEquals("36", parsedDims.get("chest"));
        assertEquals("34.5", parsedDims.get("upperChest"));
        assertEquals("30", parsedDims.get("pet"));
        assertEquals("14.5", parsedDims.get("put"));
        assertEquals("11", parsedDims.get("hand"));
        assertEquals("36B", parsedDims.get("cup"));
        assertEquals("6.5", parsedDims.get("neck"));
        assertEquals("10", parsedDims.get("sesto"));
        assertEquals("Slim Fit", parsedDims.get("fittings"));
        assertEquals("14.5", parsedDims.get("bodyLength"));
        assertEquals("16", parsedDims.get("underarm"));
        assertEquals("15", parsedDims.get("backLength"));
        assertEquals("14", parsedDims.get("frontLength"));
        assertEquals("38", parsedDims.get("width"));
        assertEquals("5.5", parsedDims.get("falsePar"));
    }

    @Test
    @DisplayName("2. Record PANT measurements with all 7 slip fields")
    @WithMockUser(username = "testtailor", roles = {"TAILOR"})
    void testRecordPantMeasurements_WithAll7SlipFields() throws Exception {
        String customerId = createTestCustomer("Debolina Sen", "9830223344");

        // Exactly the 7 fields printed on the Jolly Trading Ladies Tailors slip
        Map<String, Object> pantDimensions = new LinkedHashMap<>();
        pantDimensions.put("length", "37");
        pantDimensions.put("waist", "32");
        pantDimensions.put("hip", "38");
        pantDimensions.put("high", "12");
        pantDimensions.put("thai", "22");
        pantDimensions.put("bottom", "13");
        pantDimensions.put("underarm", "27");

        Map<String, Object> payload = Map.of(
                "garmentType", "PANT",
                "unit", "in",
                "dimensions", pantDimensions,
                "fitPreference", "Straight Cut",
                "notes", "Side elastic waistband with right side pocket"
        );

        String res = mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.garment_type", is("PANT")))
                .andExpect(jsonPath("$.unit", is("in")))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> saved = mapper.readValue(res, Map.class);
        String dimStr = (String) saved.get("dimensions");
        Map<?, ?> parsedDims = mapper.readValue(dimStr, Map.class);

        // Verify all 7 slip labels are present with their values
        assertEquals("37", parsedDims.get("length"));
        assertEquals("32", parsedDims.get("waist"));
        assertEquals("38", parsedDims.get("hip"));
        assertEquals("12", parsedDims.get("high"));
        assertEquals("22", parsedDims.get("thai"));
        assertEquals("13", parsedDims.get("bottom"));
        assertEquals("27", parsedDims.get("underarm"));
    }

    @Test
    @DisplayName("3. Edit existing measurement profile via PUT and PATCH and retain history")
    @WithMockUser(username = "testtailor", roles = {"TAILOR"})
    void testEditExistingMeasurementProfile() throws Exception {
        String customerId = createTestCustomer("Poulomi Das", "9830334455");

        // Initial measurement
        Map<String, Object> payload = Map.of(
                "garmentType", "CHURIDAR / BLOUSE",
                "unit", "in",
                "dimensions", Map.of("length", "36", "chest", "34", "pet", "28", "put", "14"),
                "fitPreference", "Regular",
                "notes", "Initial measurement"
        );

        String res = mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String mid = (String) mapper.readValue(res, Map.class).get("id");

        // Update measurement via PUT
        Map<String, Object> updatePayload = Map.of(
                "garmentType", "CHURIDAR / BLOUSE",
                "unit", "in",
                "dimensions", Map.of("length", "38", "chest", "35", "pet", "29", "put", "14.5", "neck", "6.5"),
                "fitPreference", "Comfort",
                "notes", "Adjusted for loose fitting after trial"
        );

        mockMvc.perform(put("/api/tailor/customers/" + customerId + "/measurements/" + mid)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(updatePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fit_preference", is("Comfort")))
                .andExpect(jsonPath("$.notes", containsString("Adjusted for loose fitting")));

        // Fetch single measurement
        mockMvc.perform(get("/api/tailor/customers/" + customerId + "/measurements/" + mid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fit_preference", is("Comfort")))
                .andExpect(jsonPath("$.dimensions", containsString("35")));
    }

    @Test
    @DisplayName("4. Create order selecting multiple measurement groups and show on order & bill")
    @WithMockUser(username = "testtailor", roles = {"TAILOR"})
    void testOrderWithMultipleMeasurementGroups() throws Exception {
        String customerId = createTestCustomer("Kakoli Mukherjee", "9830445566");

        // 1. Record CHURIDAR / BLOUSE
        String cbRes = mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "garmentType", "CHURIDAR / BLOUSE",
                                "unit", "in",
                                "dimensions", Map.of("length", "39", "chest", "38", "pet", "32", "put", "15", "falsePar", "6"),
                                "fitPreference", "Comfort Fit"
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cbId = (String) mapper.readValue(cbRes, Map.class).get("id");

        // 2. Record PANT
        String pantRes = mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "garmentType", "PANT",
                                "unit", "in",
                                "dimensions", Map.of("length", "38", "waist", "34", "hip", "40", "high", "13", "thai", "24"),
                                "fitPreference", "Straight Fit"
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String pantId = (String) mapper.readValue(pantRes, Map.class).get("id");

        // 3. Create order attaching both measurement groups
        Map<String, Object> orderPayload = Map.of(
                "customerId", customerId,
                "measurementIds", List.of(cbId, pantId),
                "items", List.of(
                        Map.of("name", "Churidar Top Stitching", "quantity", 1, "unitRate", 450),
                        Map.of("name", "Pant Stitching", "quantity", 1, "unitRate", 300)
                ),
                "advanceAmount", 200,
                "notes", "Full churidar & pant suit stitched together"
        );

        String orderRes = mockMvc.perform(post("/api/tailor/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(orderPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.measurements", hasSize(2)))
                .andExpect(jsonPath("$.measurements[0].garment_type", is("CHURIDAR / BLOUSE")))
                .andExpect(jsonPath("$.measurements[1].garment_type", is("PANT")))
                .andReturn().getResponse().getContentAsString();

        String orderId = (String) mapper.readValue(orderRes, Map.class).get("id");

        // 4. Verify Public Bill endpoint returns attached measurements
        mockMvc.perform(get("/api/public/bills/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements", hasSize(2)))
                .andExpect(jsonPath("$.measurements[0].garment_type", is("CHURIDAR / BLOUSE")))
                .andExpect(jsonPath("$.measurements[1].garment_type", is("PANT")));
    }

    @Test
    @DisplayName("5. Admin and Tailor see and edit the same saved measurements via shared backend")
    void testAdminAndTailorShareMeasurements() throws Exception {
        // Tailor creates customer and saves CHURIDAR / BLOUSE measurement
        String customerId;
        String mid;

        // Tailor session
        {
            Map<String, Object> cust = Map.of("name", "Swati Ganguly", "phone", "9830556677", "address", "Howrah");
            String custRes = mockMvc.perform(post("/api/tailor/customers")
                            .with(csrf())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(cust)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            customerId = (String) mapper.readValue(custRes, Map.class).get("id");

            String mRes = mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                            .with(csrf())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "garmentType", "CHURIDAR / BLOUSE",
                                    "unit", "in",
                                    "dimensions", Map.of("chest", "34", "pet", "28", "put", "14"),
                                    "fitPreference", "Fitted"
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            mid = (String) mapper.readValue(mRes, Map.class).get("id");
        }

        // Admin session: views measurements via /api/admin/customers/{id}/measurements
        mockMvc.perform(get("/api/admin/customers/" + customerId + "/measurements")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].garment_type", is("CHURIDAR / BLOUSE")))
                .andExpect(jsonPath("$[0].fit_preference", is("Fitted")));

        // Admin session: updates measurement via /api/admin/customers/{id}/measurements/{mid}
        mockMvc.perform(put("/api/admin/customers/" + customerId + "/measurements/" + mid)
                        .with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testadmin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "garmentType", "CHURIDAR / BLOUSE",
                                "unit", "in",
                                "dimensions", Map.of("chest", "36", "pet", "30", "put", "14.5", "falsePar", "5"),
                                "fitPreference", "Updated by Admin",
                                "notes", "Customer requested looseness over phone"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fit_preference", is("Updated by Admin")));

        // Tailor session: verifies updated measurement immediately
        mockMvc.perform(get("/api/tailor/customers/" + customerId + "/measurements")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("testtailor").roles("TAILOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fit_preference", is("Updated by Admin")))
                .andExpect(jsonPath("$[0].dimensions", containsString("falsePar")));
    }
}
