package com.verizon.services;

import com.verizon.model.*;
import java.util.List;

public interface PayloadInterface {
    public PayloadGroup getPayloads(String status);
    public String savePayload(Payload payload);
    public void saveRule(Payload payload);
    public void addGroupname(RequestUrlGroupName requestUrlGroupName);
    public List<Group> getGroups();
    public void addGroup(Group group);
    public void addFriendlyUrl(FriendlyUrl friendlyUrl);
    public String saveParserSettings(ParserSettings parserSettings);
    public void payLoadConsumer (Payload payload);
    public String ignorePayload(List<Payload> payloads);
    public List<Payload> Discovered();
    public List<Payload> DiscoveredUrl();
}
