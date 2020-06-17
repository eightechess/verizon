package com.verizon.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class PayloadGroup {
    private List<Payload> content;
}
