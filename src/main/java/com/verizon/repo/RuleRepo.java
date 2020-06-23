package com.verizon.repo;

import com.verizon.model.Rule;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface RuleRepo extends ElasticsearchRepository<Rule, String> {
    @Override
    List<Rule> findAll();
    Rule findByRequestUrl(String requestUrl);

}
