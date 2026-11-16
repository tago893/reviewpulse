package com.reviewpulse.search.postings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PostingListTest {

    @Test
    void appendsInOrderAndPacksFrequencies() {
        PostingList list = new PostingList(1);
        list.append(3, 1, 2);
        list.append(10, 0, 7);
        list.append(11, 70_000, 0);
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.docAt(1)).isEqualTo(10);
        assertThat(list.titleTfAt(0)).isEqualTo(1);
        assertThat(list.bodyTfAt(1)).isEqualTo(7);
        assertThat(list.titleTfAt(2)).as("capped").isEqualTo(PostingList.MAX_TF);
        assertThat(list.lastDoc()).isEqualTo(11);
    }

    @Test
    void rejectsOutOfOrderOrDuplicateDocIds() {
        PostingList list = new PostingList();
        list.append(5, 1, 0);
        assertThatThrownBy(() -> list.append(5, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.append(4, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.append(6, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gallopingAdvanceMatchesLinearScan() {
        Random random = new Random(42);
        PostingList list = new PostingList();
        int doc = 0;
        for (int i = 0; i < 5_000; i++) {
            doc += 1 + random.nextInt(20);
            list.append(doc, 0, 1);
        }
        for (int trial = 0; trial < 20_000; trial++) {
            int from = random.nextInt(list.size() + 1);
            int target = random.nextInt(doc + 50);
            assertThat(list.advance(from, target)).isEqualTo(linearAdvance(list, from, target));
        }
    }

    @Test
    void advanceOnEmptyAndExhaustedLists() {
        PostingList empty = new PostingList();
        assertThat(empty.advance(0, 10)).isZero();
        PostingList one = new PostingList();
        one.append(7, 1, 0);
        assertThat(one.advance(0, 7)).isZero();
        assertThat(one.advance(0, 8)).isEqualTo(1);
        assertThat(one.advance(1, 0)).isEqualTo(1);
    }

    private static int linearAdvance(PostingList list, int from, int target) {
        int i = from;
        while (i < list.size() && list.docAt(i) < target) {
            i++;
        }
        return i;
    }
}
