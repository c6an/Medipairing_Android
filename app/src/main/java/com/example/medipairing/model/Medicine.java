package com.example.medipairing.model;

public class Medicine {
    private final String userDefinedName; // alias
    private final String actualName;     // pill_name
    private final String idCode;         // imprint/external code
    private final int imageResId;        // fallback
    private final int pillId;            // server pill id
    private final String imageUrl;       // server image url
    private final String note;           // user note (optional)
    private final int userItemId;        // user pill item id for PATCH
    private final String category;       // pill category (for title)
    private final String efficacy;       // stored short efficacy lines
    private final String precautions;    // stored short precautions lines
    private final String storage;        // stored short storage lines

    public Medicine(String userDefinedName, String actualName, String idCode, int imageResId) {
        this(userDefinedName, actualName, idCode, imageResId, -1, null, null, -1, null);
    }

    public Medicine(String userDefinedName, String actualName, String idCode, int imageResId, int pillId, String imageUrl, String note, int userItemId) {
        this(userDefinedName, actualName, idCode, imageResId, pillId, imageUrl, note, userItemId, null);
    }

    public Medicine(String userDefinedName, String actualName, String idCode, int imageResId, int pillId, String imageUrl, String note, int userItemId, String category) {
        this(userDefinedName, actualName, idCode, imageResId, pillId, imageUrl, note, userItemId, category, null, null, null);
    }

    public Medicine(String userDefinedName, String actualName, String idCode, int imageResId, int pillId, String imageUrl, String note, int userItemId, String category, String efficacy, String precautions, String storage) {
        this.userDefinedName = userDefinedName;
        this.actualName = actualName;
        this.idCode = idCode;
        this.imageResId = imageResId;
        this.pillId = pillId;
        this.imageUrl = imageUrl;
        this.note = note;
        this.userItemId = userItemId;
        this.category = category;
        this.efficacy = efficacy;
        this.precautions = precautions;
        this.storage = storage;
    }

    public String getUserDefinedName() { return userDefinedName; }
    public String getActualName() { return actualName; }
    public String getIdCode() { return idCode; }
    public int getImageResId() { return imageResId; }
    public int getPillId() { return pillId; }
    public String getImageUrl() { return imageUrl; }
    public String getNote() { return note; }
    public int getUserItemId() { return userItemId; }
    public String getCategory() { return category; }
    public String getEfficacy() { return efficacy; }
    public String getPrecautions() { return precautions; }
    public String getStorage() { return storage; }
}
