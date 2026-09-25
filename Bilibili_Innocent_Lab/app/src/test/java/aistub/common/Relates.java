package aistub.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Relates {
    private final List<RelateCard> cards;

    public Relates(List<RelateCard> cards) { this.cards = new ArrayList<>(cards); }

    public List<RelateCard> getCardsList() { return Collections.unmodifiableList(cards); }

    public static Builder newBuilder(Relates original) { return new Builder(original); }

    public static final class Builder {
        private final List<RelateCard> cards;

        Builder(Relates original) { cards = new ArrayList<>(original.cards); }

        public Builder clearCards() { cards.clear(); return this; }

        public Builder addAllCards(Iterable<? extends RelateCard> values) {
            for (RelateCard value : values) cards.add(value);
            return this;
        }

        public Relates build() { return new Relates(cards); }
    }
}
