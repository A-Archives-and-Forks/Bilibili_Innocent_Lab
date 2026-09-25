package aistub.common;

public class UgcIntroduction {
    private final Neutral neutral;

    public UgcIntroduction(Neutral neutral) { this.neutral = neutral; }

    public boolean hasNeutral() { return neutral != null; }
    public Neutral getNeutral() { return neutral; }
}
