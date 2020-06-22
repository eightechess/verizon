package com.verizon.services;

import com.verizon.model.*;
import com.verizon.repo.*;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
public class PayloadImp implements PayloadInterface {

    public static final List<String> STATUSES = Arrays.asList(Status.values()).stream().map(Status::name).collect(Collectors.toList());

    @Autowired
    private PayloadRepo payloadRepo;

    @Autowired
    private RuleRepo ruleRepo;

    @Autowired
    private RequestUrlGroupNameRepo requestUrlGroupNameRepo;

    @Autowired
    private FriendlyUrlRepo friendlyUrlRepo;

    @Autowired
    private GroupRepo groupRepo;

    @Autowired
    private KafkaTemplate kafkaTemplate;

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Override
    public PayloadGroup getPayloads(@RequestParam(required = false) String status){
        log.info("Status is: "+status);
        PayloadGroup result = new PayloadGroup();
        if(STATUSES.contains(status)){
            Status newStatus = Status.valueOf(status);
            log.info("Find By Status {}", newStatus);
            result.setContent(payloadRepo.findByStatus(newStatus));
        }else{
            log.info("Find All");
            result.setContent(payloadRepo.findAll());
        }
        return result;
    }

    @Override
    public String savePayload(Payload payload) {
        log.info("savePayload: {}", payload);
        payload.setCreated_last(new Date());
        payloadRepo.save(payload);
        kafkaTemplate.send("REQUEST_SAMPLES",payload);
        return "Parameter "+payload.getRequestUrl() +" saved";
    }

    @Override
    public void saveRule(Payload payload) {
        Rule rule = new Rule();
        BeanUtils.copyProperties(rule, payload);
        log.info("saveRule: {}", rule);
        ruleRepo.save(rule);
        kafkaTemplate.send("RULES_SAMPLES",rule);
        log.info("Rule  "+rule.getRequestUrl() +" saved");
    }

    @Override
    public void addGroupname(RequestUrlGroupName requestUrlGroupName) {
        requestUrlGroupNameRepo.save(requestUrlGroupName);
        log.info("RequestUrl and GroupName "+requestUrlGroupName.getRequestUrl() +" saved");
    }

    @Override
    public List<Group> getGroups() {
        return groupRepo.findAll();
    }

    @Override
    public void addGroup(Group group) {
        groupRepo.save(group);
        log.info("GroupName "+group.getGroupName() +" saved");
    }

    @Override
    public void addFriendlyUrl(FriendlyUrl friendlyUrl) {
        friendlyUrlRepo.save(friendlyUrl);
        log.info("Friendly Url "+friendlyUrl.getRequestUrl() +" saved");
    }

    @Override
    public String saveParserSettings(ParserSettings parserSettings) {
        log.info("saveParserSettings {}", parserSettings);
        kafkaTemplate.send("PARSER_SETTINGS", "RELOAD_TRAFFIC_CAPTURE", parserSettings);
        return "Parameter "+parserSettings +" saved";
    }

    @Override
    public void payLoadConsumer(Payload payload) {
        payload.setStatus(Status.DISCOVERED);
        payload.setLastUpdated(new Date());
        validate(payload);
        payloadRepo.save(payload);

        List<DoNotReportUrl> doNotReportUrlList = new ArrayList<>();
        for(Payload onePayload: payloadRepo.findAll()){
            DoNotReportUrl doNotReportUrl = new DoNotReportUrl();
            doNotReportUrl.setUrl(onePayload.getRequestUrl());
            doNotReportUrl.setGroup("doNotReport");
            doNotReportUrlList.add(doNotReportUrl);
        }
        DoNotReportUrlList doNotReportList = new DoNotReportUrlList();
        doNotReportList.  setDoNotReportUrlList(doNotReportUrlList);
        kafkaTemplate.send("PARSER_SETTINGS","RELOAD_DO_NOT_REPORT_LINKS", doNotReportList);
        log.info("Payload: " + doNotReportList +" saved");
    }

    private void validate(Payload payload) {
        Set<ConstraintViolation<Payload>> violation = validator.validate(payload);
        if (violation.size() > 0)
            throw new RuntimeException("Validation error: " + violation.stream().map(e->e.getMessage()).collect(Collectors.joining(",")));
    }

    @Override
    public String ignorePayload(List<Payload> payloads) {
        log.info("Payloads " +payloads.size()+" ignored");
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
        return payloadRepo.findByStatus(Status.DISCOVERED);
    }

    @Override
    public List<Payload> DiscoveredUrl() {
        return payloadRepo.payLoadUrls(Status.DISCOVERED);
    }
}
