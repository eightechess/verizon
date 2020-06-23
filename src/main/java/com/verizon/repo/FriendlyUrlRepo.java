package com.verizon.repo;

import com.verizon.model.FriendlyUrl;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface FriendlyUrlRepo extends ElasticsearchRepository<FriendlyUrl, String> {
    @Override
    List<FriendlyUrl> findAll();
    FriendlyUrl findByRequestUrl(String requestUrl);

}
