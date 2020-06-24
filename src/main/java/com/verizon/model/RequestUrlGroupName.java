package com.verizon.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;

@Getter
@Setter
@ToString
@Document(indexName= "groupnames", shards = 2)
public class RequestUrlGroupName {
    @Id
    private String requestUrl;
    private String groupName;
}
