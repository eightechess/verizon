package com.verizon.repo;

import com.verizon.model.Rule;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface RuleRepo extends ElasticsearchRepository<Rule, Long> {
    @Override
    List<Rule> findAll();
    Rule findByName(String name);

}
