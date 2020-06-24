package com.verizon.controller;

import com.verizon.model.Rule;
import com.verizon.services.rule.RuleInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

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

    @CrossOrigin
    @GetMapping("/deleterule")
    public void deleteRule(@RequestBody Long id) {
        ruleInterface.deleteRule(id);
    }

    @CrossOrigin
    @GetMapping("/getrule")
    public Rule getRule(@RequestBody String name) {
        return ruleInterface.getRule(name);
    }

    @CrossOrigin
    @GetMapping("/getrules")
    public List<Rule> getRules() {
        return ruleInterface.getRules();
    }
}
