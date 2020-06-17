package com.verizon.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verizon.model.Payload;
import com.verizon.model.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Date;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@WebMvcTest(PayloadController.class)
class PayloadControllerTest {

    private PayloadController payloadController;

    private MockMvc mvc;

    @Test
    void getPayloads() throws Exception {

        RequestBuilder requestBuilder = MockMvcRequestBuilders.get("/getAllParameters");
        MvcResult result = mvc.perform(requestBuilder).andReturn();
        assertEquals("" ,result.getResponse().getContentAsString());
    }

    @Test
    void savePayload() throws Exception {
        Payload payload = new Payload();
        payload.setRequestUrl("https://www.verizonwireless.com/?intcm3p=Vu");
        payload.setPayload("PayLoad PayLoad PayLoad");
        HashMap<String,String> map=new HashMap<String,String>();
        map.put("Fruit","Mango");  //Put elements in Map
        map.put("Animal","Elephant");
        map.put("Artist","Akon");
        map.put("Country","USA");
        payload.setRequestHeaders(map);
        payload.setStatus(Status.DISCOVERED);
        payload.setLastUpdated(new Date());

        String jsonData = this.mapToJson(payload);

        RequestBuilder request = MockMvcRequestBuilders.post("/addParameters");
        MvcResult result = mvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        String outputInJson = response.getContentAsString();

        assertEquals(outputInJson,jsonData);
        assertEquals(HttpStatus.OK.value(), response.getStatus());

    }

    @Test
    void saveParserSettings() {
    }

    @Test
    void payLoadConsumer() {
    }

    @Test
    void ignorePayload() {
    }

    @Test
    void discovered() {
    }

    @Test
    void discoveredUrl() {
    }

    private String mapToJson(Object object) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(object);
    }
}
