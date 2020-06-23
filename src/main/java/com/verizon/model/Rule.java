package com.verizon.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
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
	private long id;
	private String name;
	private String type;
	private String uri;
	private Map<String,String> parameterKeys;
	private Map<String,String> parameterDictionary;
}

