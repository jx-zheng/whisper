package com.jxzheng.whisper.media;

import static org.junit.Assert.assertEquals;

import java.awt.Point;

import org.junit.Test;

public class PointComparatorTest {
    private final PointComparator testComparator = new PointComparator();

    @Test
    public void SameXDifferentY() {
        Point a = new Point(5, 10);
        Point b = new Point(5, 12);

        int result = testComparator.compare(a, b);
        assertEquals(-1, result);
    }

    @Test
    public void SameYDifferentX() {
        Point a = new Point(6, 10);
        Point b = new Point(5, 10);

        int result = testComparator.compare(a, b);
        assertEquals(1, result);
    }

    @Test
    public void SameXSameY() {
        Point a = new Point(100, 90);
        Point b = new Point(100, 90);

        int result = testComparator.compare(a, b);
        assertEquals(0, result);
    }

    @Test
    public void DifferentXDifferentY() {
        Point a = new Point(101, 85);
        Point b = new Point(100, 90);

        int result = testComparator.compare(a, b);
        assertEquals(-1, result);
    }

}
