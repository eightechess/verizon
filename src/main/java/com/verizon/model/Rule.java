package com.verizon.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.elasticsearch.annotations.Document;

import java.util.Date;
import java.util.Map;

@Getter
@Setter
@ToString

@Document(indexName= "rules", shards = 2)
public class Rule {
	@Id
	private String requestUrl;
	private Map<String,String> requestHeaders;
	private String payload;
	private Status status;
	@LastModifiedDate
	private Date lastUpdated;
}

