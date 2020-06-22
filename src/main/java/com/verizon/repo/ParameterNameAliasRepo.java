package com.verizon.repo;

import com.verizon.model.ParameterNameAlias;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;


public interface ParameterNameAliasRepo extends ElasticsearchRepository<ParameterNameAlias, String> {

}
