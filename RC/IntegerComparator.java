/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
//package roucairolcarvahlobasicversion;

import java.util.Comparator;

/**
 *
 * @author Praveen MT
 */
class IntegerComparator implements Comparator<Integer> {

    public IntegerComparator() {
    }

    @Override
    public int compare(Integer o1, Integer o2) {
        if (o1 < o2) {
            return -1;
        }
        else if (o1 > o2) {
            return 1;
        }
        return 0;
    }
    
}
