package com.verizon.controller;

import com.verizon.model.FriendlyUrl;
import com.verizon.services.friendlyUri.FriendlyUrlInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Log4j2
@RestController
public class FriendlyUrlController {

    @Autowired
    private FriendlyUrlInterface friendlyUrlInterface;

    @PostMapping("/addfriendlyurl")
    @Async("threadPoolTaskExecutor")
    public void addFriendlyUrl(@RequestBody FriendlyUrl friendlyUrl) {
        friendlyUrlInterface.saveFriendlyUrl(friendlyUrl);
    }

    @CrossOrigin
    @GetMapping("/getfriendlyurl")
    public FriendlyUrl getFriendlyUrl(String friendlyUrl) {
        return friendlyUrlInterface.getFriendlyUrl(friendlyUrl);
    }

    @CrossOrigin
    @GetMapping("/getfriendlyurls")
    public List<FriendlyUrl> getFriendlyUrls() {
       return friendlyUrlInterface.getFriendlyUrls();
    }

    @PostMapping("/editfriendlyurl")
    @Async("threadPoolTaskExecutor")
    public void editFriendlyUrl(@RequestBody FriendlyUrl friendlyUrl) {
        friendlyUrlInterface.editFriendlyUrl(friendlyUrl);
    }

    @CrossOrigin
    @GetMapping("/deletefriendlyurl")
    public void deleteFriendlyUrl(@RequestBody String requestUrl) {
        friendlyUrlInterface.deleteFriendlyUrl(requestUrl);
    }
}
