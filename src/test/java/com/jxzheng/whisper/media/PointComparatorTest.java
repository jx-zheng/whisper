package com.jxzheng.whisper.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;

import org.junit.jupiter.api.Test;

class PointComparatorTest {

    private final PointComparator comparator = new PointComparator();

    @Test
    void sameXDifferentY() {
        Point a = new Point(5, 10);
        Point b = new Point(5, 12);
        assertTrue(comparator.compare(a, b) < 0);
    }

    @Test
    void sameYDifferentX() {
        Point a = new Point(6, 10);
        Point b = new Point(5, 10);
        assertTrue(comparator.compare(a, b) > 0);
    }

    @Test
    void sameXSameY() {
        Point a = new Point(100, 90);
        Point b = new Point(100, 90);
        assertEquals(0, comparator.compare(a, b));
    }

    @Test
    void differentXDifferentYOrdersByYFirst() {
        Point a = new Point(101, 85);
        Point b = new Point(100, 90);
        assertTrue(comparator.compare(a, b) < 0);
    }
}
