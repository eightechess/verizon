package com.verizon.repo;

import com.verizon.model.Group;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface GroupRepo extends ElasticsearchRepository<Group, Integer> {
    @Override
    List<Group> findAll();
}
