package com.edith.rdc.model;

import java.util.Map;

public class SamlUserInfo {
    private String nameID;
    private String email;
    private String displayName;
    private Map<String, String> attributes;

    public SamlUserInfo() {}

    public String getNameID() { return nameID; }
    public void setNameID(String nameID) { this.nameID = nameID; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
}
