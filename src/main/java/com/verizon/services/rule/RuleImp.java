package com.verizon.services.rule;

import com.verizon.model.Payload;
import com.verizon.model.Rule;
import com.verizon.repo.RuleRepo;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Log4j2
public class RuleImp implements  RuleInterface {

    @Autowired
    private RuleRepo ruleRepo;

    @Autowired
    private KafkaTemplate kafkaTemplate;

    @Override
    public void saveRule(Payload payload) {
        Rule rule = new Rule();
        BeanUtils.copyProperties(rule, payload);
        log.info("saveRule: {}", rule);
        ruleRepo.save(rule);
        kafkaTemplate.send("RULES_SAMPLES",rule);
        log.info("Rule  "+rule.getName() +" saved");
    }

    @Override
    public Rule getRule(String requestUrl) {
        return ruleRepo.findByRequestUrl(requestUrl);
    }

    @Override
    public void editRule(Rule rule) {
         ruleRepo.save(rule);
    }

    @Override
    public void deleteRule(Long id) {
        ruleRepo.deleteById(id);
    }

    @Override
    public List<Rule> getRules() {
        return ruleRepo.findAll();
    }

}
