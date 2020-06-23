package com.verizon.services.group;

import com.verizon.model.Group;
import com.verizon.model.RequestUrlGroupName;
import com.verizon.repo.GroupRepo;
import com.verizon.repo.RequestUrlGroupNameRepo;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Log4j2
public class GroupImp implements GroupInterface {

    @Autowired
    private GroupRepo groupRepo;

    @Autowired
    private RequestUrlGroupNameRepo requestUrlGroupNameRepo;

    @Override
    public void addGroupname(RequestUrlGroupName requestUrlGroupName) {
        requestUrlGroupNameRepo.save(requestUrlGroupName);
        log.info("RequestUrl and GroupName "+requestUrlGroupName.getRequestUrl() +" saved");
    }

    @Override
    public void addGroup(Group group) {
        groupRepo.save(group);
        log.info("GroupName "+group.getGroupName() +" saved");
    }

    @Override
    public Group getGroup(String groupName) {
        return groupRepo.findByGroupName(groupName);
    }

    @Override
    public void editGroup(Group group) {
        groupRepo.save(group);

    }

    @Override
    public void deleteGroup(String groupName) {
        groupRepo.deleteById(groupName);
    }

    @Override
    public List<Group> getGroups() {
        return groupRepo.findAll();
    }

}
