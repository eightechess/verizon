package com.verizon.controller;

import com.verizon.model.Group;
import com.verizon.model.RequestUrlGroupName;
import com.verizon.services.group.GroupInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Log4j2
@RestController
public class GroupController {

    @Autowired
    private GroupInterface groupInterface;

    @PostMapping("/addgroupname")
    @Async("threadPoolTaskExecutor")
    public void addGroupname(@RequestBody RequestUrlGroupName requestUrlGroupName) {
        groupInterface.addGroupname(requestUrlGroupName);
    }

    @CrossOrigin
    @GetMapping("/getgroupnames")
    public List<Group> getGroups(){
        return groupInterface.getGroups();
    }

    @CrossOrigin
    @PostMapping("/addgroup")
    public void getGroups(@RequestBody Group  group){
        groupInterface.addGroup(group);
    }

    @CrossOrigin
    @PostMapping("/editgroup")
    public void editGroup(@RequestBody Group  group){
        groupInterface.editGroup(group);
    }

    @CrossOrigin
    @PostMapping("/deletegroup")
    public void editGroup(@RequestBody String  groupName){
        groupInterface.deleteGroup(groupName);
    }

}
