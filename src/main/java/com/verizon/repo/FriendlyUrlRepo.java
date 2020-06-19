package com.verizon.repo;

import com.verizon.model.FriendlyUrl;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface FriendlyUrlRepo extends ElasticsearchRepository<FriendlyUrl, String> {

}
