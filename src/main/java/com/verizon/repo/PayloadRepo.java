package com.verizon.repo;

import com.verizon.model.Payload;
import com.verizon.model.Status;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PayloadRepo extends ElasticsearchRepository<Payload, String> {
    List<Payload> findByStatus(Status status);
    @Query(value = "SELECT  e.requestUrl FROM payload  e WHERE e.status= :status")
    List<Payload> payLoadUrls(@Param("status") Status status);;
}
