package com.verizon.controller;

import com.verizon.model.FriendlyUrl;
import com.verizon.model.Rule;
import com.verizon.services.friendlyUri.FriendlyUrlInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/getfriendlyurl")
    @Async("threadPoolTaskExecutor")
    public FriendlyUrl getFriendlyUrl(String friendlyUrl) {
        return friendlyUrlInterface.getFriendlyUrl(friendlyUrl);
    }

    @GetMapping("/getfriendlyurls")
    @Async("threadPoolTaskExecutor")
    public List<FriendlyUrl> getFriendlyUrls() {
       return friendlyUrlInterface.getFriendlyUrls();
    }

    @PostMapping("/editfriendlyurl")
    @Async("threadPoolTaskExecutor")
    public void editFriendlyUrl(@RequestBody FriendlyUrl friendlyUrl) {
        friendlyUrlInterface.editFriendlyUrl(friendlyUrl);
    }

    @GetMapping("/deletefriendlyurl")
    @Async("threadPoolTaskExecutor")
    public void deleteFriendlyUrl(@RequestBody String requestUrl) {
        friendlyUrlInterface.deleteFriendlyUrl(requestUrl);
    }
}
