package com.verizon.services.group;

import com.verizon.model.Group;
import com.verizon.model.RequestUrlGroupName;

import java.util.List;

public interface GroupInterface {
    public void addGroupname(RequestUrlGroupName requestUrlGroupName);
    public void addGroup(Group group);
    public Group getGroup(String groupName);
    public void editGroup(Group group);
    public void deleteGroup(String groupName);
    public List<Group> getGroups();
}
