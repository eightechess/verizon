package com.verizon.repo;

import com.verizon.model.Payload;
import com.verizon.model.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Date;
import java.util.HashMap;
import java.util.Optional;

@DataJpaTest
public class PayloadRepoTest {

    @Autowired
    private TestEntityManager  testEntityManager;

    @Autowired
    private PayloadRepo payloadRepo;

    @Test
    public void savePayload(){
        Payload payload = getPayload();
        Payload saved = testEntityManager.persist(payload);
        Optional<Payload> fromDb = payloadRepo.findById(saved.getRequestUrl());
        assert(saved).equals(fromDb);
    }

    private Payload getPayload() {
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
        return payload;
    }
}
