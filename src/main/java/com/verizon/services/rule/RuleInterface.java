package com.verizon.services.rule;

import com.verizon.model.Payload;
import com.verizon.model.Rule;

import java.util.List;

public interface RuleInterface {
    public void saveRule(Payload payload);
    public Rule getRule(String requestUrl);
    public void editRule(Rule rule);
    public void deleteRule(String requestUrl);
    public List<Rule> getRules();
}
