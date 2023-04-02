package com.programminghut.realtime_object;

import android.graphics.RectF;

public class Detection {
    public final RectF box;
    public final String label;
    public final float confidence;  // Added this field
    public final int type;         // 0: vehicle, 1: sign

    public Detection(RectF box, String label, float confidence, int type) {
        this.box = box;
        this.label = label;
        this.confidence = confidence;
        this.type = type;
    }
}