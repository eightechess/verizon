package com.verizon.repo;

import com.verizon.model.Group;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface GroupRepo extends ElasticsearchRepository<Group, String> {
    @Override
    List<Group> findAll();
    Group findByGroupName(String groupName);
}
