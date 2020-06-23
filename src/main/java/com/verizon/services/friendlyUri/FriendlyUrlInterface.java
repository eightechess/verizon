package com.verizon.services.friendlyUri;

import com.verizon.model.FriendlyUrl;

import java.util.List;

public interface FriendlyUrlInterface {
    public void saveFriendlyUrl(FriendlyUrl friendlyUrl);
    public FriendlyUrl getFriendlyUrl(String requestUrl);
    public void editFriendlyUrl(FriendlyUrl friendlyUrl);
    public void deleteFriendlyUrl(String requestUrl);
    public List<FriendlyUrl> getFriendlyUrls();
}
