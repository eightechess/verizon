package com.verizon.services.rule;

import com.verizon.model.Payload;
import com.verizon.model.Rule;

import java.util.List;

public interface RuleInterface {
    public void saveRule(Rule rule);
    public Rule getRule(String requestUrl);
    public void editRule(Rule rule);
    public void deleteRule(Long id);
    public List<Rule> getRules();
}
