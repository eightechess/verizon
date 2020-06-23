package com.verizon.services.payload;

import com.verizon.model.*;

import java.util.List;

public interface PayloadInterface {
    public PayloadGroup getPayloads(String status);
    public String savePayload(Payload payload);
    public String saveParserSettings(ParserSettings parserSettings);
    public void payLoadConsumer (Payload payload);
    public String ignorePayload(List<Payload> payloads);
    public List<Payload> Discovered();
    public List<Payload> DiscoveredUrl();
}
