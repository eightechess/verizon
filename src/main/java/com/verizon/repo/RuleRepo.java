package com.verizon.repo;

import com.verizon.model.Rule;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface RuleRepo extends ElasticsearchRepository<Rule, String> {

}
