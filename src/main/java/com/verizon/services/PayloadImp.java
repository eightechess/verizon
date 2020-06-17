package com.verizon.services;

import com.verizon.model.DoNotReportUrl;
import com.verizon.model.ParserSettings;
import com.verizon.model.Payload;
import com.verizon.model.Status;
import com.verizon.repo.PayloadRepo;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Log4j2
public class PayloadImp implements PayloadInterface {

    @Autowired
    private PayloadRepo payloadRepo;

    @Autowired
    private KafkaTemplate kafkaTemplate;

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Override
    public Iterable<Payload> getPayloads(String status){
        Status newstatus = Status.valueOf(status);
        log.info("Status is: "+newstatus);
        if(status != null) {
            return payloadRepo.findByStatus(newstatus);
        }
        return payloadRepo.findAll();
    }

    @Override
    public String savePayload(Payload payload) {
        log.info("savePayload: {}", payload);
        payloadRepo.save(payload);
        kafkaTemplate.send("REQUEST_SAMPLES",payload);
        return "Parameter "+payload.getRequestUrl() +" saved";
    }

    @Override
    public String saveParserSettings(ParserSettings parserSettings) {
        log.info("saveParserSettings {}", parserSettings);
        kafkaTemplate.send("PARSER_SETTINGS", "RELOAD_TRAFFIC_CAPTURE", parserSettings);
        return "Parameter "+parserSettings +" saved";
    }

    @Override
    public void payLoadConsumer(Payload payload) {
     //   validate(payload);
        payloadRepo.save(payload);
        List<Payload> payloadList= new ArrayList<Payload>();
        payloadRepo.findAll().forEach(payloadList::add);
        DoNotReportUrl doNotReportUrl = new DoNotReportUrl();
        List<DoNotReportUrl> doNotReportUrlList = new ArrayList<>();
        for(int i = 0;i <payloadList.size(); i++){
            doNotReportUrl.setUrl(payloadList.get(i).getRequestUrl());
            doNotReportUrl.setGroup("orderStatus");
            doNotReportUrlList.add(i,doNotReportUrl);
        }
        kafkaTemplate.send("PARSER_SETTINGS","RELOAD_DO_NOT_REPORT_LINKS", doNotReportUrlList);
        log.info("Payload: " + doNotReportUrlList +" saved");
    }

    @Override
    public String ignorePayload(List<Payload> payloads) {
        List<Payload> payloadList = new ArrayList<>();
        for (Payload payload : payloads) {
            payload.setStatus(Status.IGNORED);
            payload.setLastUpdated(new Date());
            payloadList.add(payload);
        }
        kafkaTemplate.send("REQUEST_SAMPLES",payloadList);
        return "Payloads "+payloads.size()+" ignored";
    }

    @Override
    public List<Payload> Discovered() {
        return null;
    }

    @Override
    public List<Payload> DiscoveredUrl() {
        return null;
    }

    private void validate(Payload payload) {
        Set<ConstraintViolation<Payload>> violation = validator.validate(payload);
        if (violation.size() > 0)
            throw new RuntimeException("Validation error: " + violation.stream().map(e->e.getMessage()).collect(Collectors.joining(",")));
    }

}
