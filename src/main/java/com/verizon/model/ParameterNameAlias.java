package com.verizon.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;

import java.util.List;

@Getter
@Setter
@ToString

@Document(indexName= "entityAliasPair", shards = 2)

public class ParameterNameAlias {

    @Id
    String EntityName;
    List<String> Alias;
}
