package aistub.common;

public class Owner {
    private final String title;
    private final long mid;

    public Owner(String title, long mid) {
        this.title = title;
        this.mid = mid;
    }

    public String getTitle() { return title; }
    public long getMid() { return mid; }
}
