package aistub.common;

public class CardBasicInfo {
    private final long id;
    private final String uri;
    private final Owner author;

    public CardBasicInfo(long id, String uri, Owner author) {
        this.id = id;
        this.uri = uri;
        this.author = author;
    }

    public long getId() { return id; }
    public String getUri() { return uri; }
    public boolean hasAuthor() { return author != null; }
    public Owner getAuthor() { return author; }
}
