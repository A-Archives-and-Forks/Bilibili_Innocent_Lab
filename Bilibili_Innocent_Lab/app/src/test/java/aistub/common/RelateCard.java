package aistub.common;

public class RelateCard {
    private final int type;
    private final CardBasicInfo basicInfo;

    public RelateCard(int type, CardBasicInfo basicInfo) {
        this.type = type;
        this.basicInfo = basicInfo;
    }

    public int getRelateCardTypeValue() { return type; }
    public boolean hasBasicInfo() { return basicInfo != null; }
    public CardBasicInfo getBasicInfo() { return basicInfo; }
}
