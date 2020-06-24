package com.verizon.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;

@Data
@Document(indexName= "friendlyurl", shards = 2)
public class FriendlyUrl {
    @Id
    private String requestUrl;
    private  String friendlyUrl;
}
