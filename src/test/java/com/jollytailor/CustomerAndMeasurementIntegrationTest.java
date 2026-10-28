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
class CustomerAndMeasurementIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    @DisplayName("Tailor can create customer, search customer, update customer, and record measurements")
    @WithMockUser(username = "testtailor", roles = {"TAILOR"})
    void testCustomerAndMeasurementWorkflow() throws Exception {
        // 1. Create a customer
        Map<String, Object> customerData = Map.of(
                "name", "Ananya Banerjee",
                "phone", "9830012345",
                "address", "12/A Southern Avenue, Kolkata",
                "notes", "Prefers cotton lining, regular fit"
        );

        String customerRes = mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(customerData)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name", is("Ananya Banerjee")))
                .andExpect(jsonPath("$.phone", is("9830012345")))
                .andExpect(jsonPath("$.address", containsString("Southern Avenue")))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> createdCustomer = mapper.readValue(customerRes, Map.class);
        String customerId = (String) createdCustomer.get("id");

        // 2. Search for the customer by phone
        mockMvc.perform(get("/api/tailor/customers?q=9830012345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].name", is("Ananya Banerjee")));

        // 3. Update customer address
        Map<String, Object> updateData = Map.of(
                "name", "Ananya Banerjee",
                "phone", "9830012345",
                "address", "15/B Lake Road, Kolkata",
                "notes", "Updated address"
        );

        mockMvc.perform(patch("/api/tailor/customers/" + customerId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(updateData)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address", containsString("Lake Road")));

        // 4. Save garment-specific measurements against customer
        Map<String, Object> dimensions = Map.of(
                "shoulder", "14.5",
                "bust", "36",
                "blouseWaist", "30",
                "blouseLength", "14",
                "sleeveLength", "10",
                "armhole", "16",
                "frontNeck", "6.5",
                "backNeck", "8.5"
        );

        Map<String, Object> measureData = Map.of(
                "garmentType", "Blouse",
                "unit", "in",
                "dimensions", dimensions,
                "fitPreference", "Fitted",
                "notes", "Princess cut with padded cups"
        );

        mockMvc.perform(post("/api/tailor/customers/" + customerId + "/measurements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(measureData)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.garment_type", is("Blouse")))
                .andExpect(jsonPath("$.fit_preference", is("Fitted")))
                .andExpect(jsonPath("$.unit", is("in")));

        // 5. Retrieve measurement history for customer
        mockMvc.perform(get("/api/tailor/customers/" + customerId + "/measurements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].garment_type", is("Blouse")))
                .andExpect(jsonPath("$[0].dimensions", containsString("bust")));

        // 6. Look up customer by phone
        mockMvc.perform(get("/api/tailor/customers/by-phone?phone=9830012345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(customerId)))
                .andExpect(jsonPath("$.name", is("Ananya Banerjee")));

        // 7. Look up customer orders endpoint
        mockMvc.perform(get("/api/tailor/customers/" + customerId + "/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Validation fails when required customer fields are missing")
    @WithMockUser(username = "testtailor", roles = {"TAILOR"})
    void testCustomerValidationFails() throws Exception {
        Map<String, Object> badData = Map.of("name", "", "phone", "");
        mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(badData)))
                .andExpect(status().isBadRequest());
    }
}
