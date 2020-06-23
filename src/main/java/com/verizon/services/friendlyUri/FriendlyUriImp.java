package com.verizon.services.friendlyUri;

import com.verizon.model.FriendlyUrl;
import com.verizon.repo.FriendlyUrlRepo;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Log4j2
public class FriendlyUriImp  implements FriendlyUrlInterface {

    @Autowired
    private FriendlyUrlRepo friendlyUrlRepo;

    @Override
    public void saveFriendlyUrl(FriendlyUrl friendlyUrl) {
        friendlyUrlRepo.save(friendlyUrl);
    }

    @Override
    public FriendlyUrl getFriendlyUrl(String requestUrl) {
        return friendlyUrlRepo.findByRequestUrl(requestUrl);
    }

    @Override
    public void editFriendlyUrl(FriendlyUrl friendlyUrl) {
        friendlyUrlRepo.save(friendlyUrl);
    }

    @Override
    public void deleteFriendlyUrl(String requestUrl) {
        friendlyUrlRepo.deleteById(requestUrl);
    }

    @Override
    public List<FriendlyUrl> getFriendlyUrls() {
        return friendlyUrlRepo.findAll();
    }
}
