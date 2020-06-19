package com.verizon.repo;

import com.verizon.model.RequestUrlGroupName;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface RequestUrlGroupNameRepo extends ElasticsearchRepository<RequestUrlGroupName, String> {
}
