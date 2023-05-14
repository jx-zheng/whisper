package com.jxzheng.whisper.media;

import java.awt.Point;
import java.util.Comparator;

public class PointComparator implements Comparator<Point> {

    @Override
    public int compare(Point p1, Point p2) {
        int result = Integer.compare(p1.y, p2.y);
        if (result == 0) {
            result = Integer.compare(p1.x, p2.x);
        }
        return result;
    }

}
