package com.verizon.services;

import com.verizon.model.ParserSettings;
import com.verizon.model.Payload;

import java.util.List;

public interface PayloadInterface {
    public Iterable<Payload> getPayloads(String status);
    public String savePayload(Payload payload);
    public String saveParserSettings(ParserSettings parserSettings);
    public void payLoadConsumer (Payload payload);
    public String ignorePayload(List<Payload> payloads);
    public List<Payload> Discovered();
    public List<Payload> DiscoveredUrl();
}
