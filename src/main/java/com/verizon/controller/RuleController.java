package com.verizon.controller;

import com.verizon.model.Payload;
import com.verizon.model.Rule;
import com.verizon.services.payload.PayloadInterface;
import com.verizon.services.rule.RuleInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Log4j2
@RestController
public class RuleController {

    @Autowired
    private RuleInterface ruleInterface;

    @PostMapping("/addrule")
    @Async("threadPoolTaskExecutor")
    public void saveRule(@RequestBody Rule rule) {
        ruleInterface.saveRule(rule);
    }

    @PostMapping("/editrule")
    @Async("threadPoolTaskExecutor")
    public void editRule(@RequestBody Rule rule) {
        ruleInterface.editRule(rule);
    }

    @GetMapping("/deleterule")
    @Async("threadPoolTaskExecutor")
    public void deleteRule(@RequestBody Long id) {
        ruleInterface.deleteRule(id);
    }

    @GetMapping("/getrule")
    @Async("threadPoolTaskExecutor")
    public Rule getRule(@RequestBody String requestUrl) {
        return ruleInterface.getRule(requestUrl);
    }

    @GetMapping("/getrules")
    @Async("threadPoolTaskExecutor")
    public List<Rule> getRules() {
        return ruleInterface.getRules();
    }
}
