/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.csa.rstb.biomass.gpf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Central statistics measures (mean / median / mode) over a list of
 * float values. Returns {@code invalidVal} when the list is empty.
 */
public class CentralStatisticMeasure {

    private final ArrayList<Float> arrayList;
    private final float invalidVal;

    public CentralStatisticMeasure(final ArrayList<Float> arrayList, final float invalidVal) {
        this.arrayList = arrayList;
        this.invalidVal = invalidVal;
    }

    public float getMean() {
        final int listSize = arrayList.size();
        if (listSize <= 0) {
            return invalidVal;
        }
        float sum = 0f;
        for (Float val : arrayList) {
            sum += val;
        }
        return sum / listSize;
    }

    public float getMedian() {
        final int listSize = arrayList.size();
        if (listSize <= 0) {
            return invalidVal;
        }
        if (listSize == 1) {
            return arrayList.get(0);
        }
        Collections.sort(arrayList);
        if (listSize % 2 == 0) {
            return (arrayList.get(listSize / 2) + arrayList.get(listSize / 2 - 1)) / 2.0f;
        }
        return arrayList.get(listSize / 2);
    }

    public float getMode() {
        final int listSize = arrayList.size();
        if (listSize <= 0) {
            return invalidVal;
        }

        final HashMap<Float, Integer> counter = new HashMap<>();
        for (Float a : arrayList) {
            counter.merge(a, 1, Integer::sum);
        }

        int highestCnt = 0;
        final HashSet<Float> modeSet = new HashSet<>();
        for (Map.Entry<Float, Integer> entry : counter.entrySet()) {
            if (entry.getValue() > highestCnt) {
                modeSet.clear();
                modeSet.add(entry.getKey());
                highestCnt = entry.getValue();
            } else if (entry.getValue() == highestCnt) {
                modeSet.add(entry.getKey());
            }
        }

        float sum = 0f;
        for (Float a : modeSet) {
            sum += a;
        }
        return sum / modeSet.size();
    }
}
