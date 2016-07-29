/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
//package roucairolcarvahlobasicversion;

/**
 *
 * @author Praveen MT
 */
public class VectorClass {

    public static int[] vectorTime;

    public static synchronized int[] getVectorTime() {
        return VectorClass.vectorTime;
    }

    public static synchronized void setVectorTime(int[] aVectorTime) {
        VectorClass.vectorTime = aVectorTime;
    }
    
    public static synchronized void initialize(int noOfProcesses) {
	VectorClass.vectorTime = new int[noOfProcesses];
	for (int i = 0 ; i < noOfProcesses ; i++) {
            VectorClass.vectorTime[i] = 0;
        }
    }
    
    public static synchronized int[] increment(int sourceID){
        VectorClass.vectorTime[sourceID] = VectorClass.vectorTime[sourceID] + 1;
        return VectorClass.getVectorTime();
    }

    public static synchronized void update(int myProcessId,int[] numericalTimestamp) {
	for (int a = 0 ; a < numericalTimestamp.length ; a++) {
		VectorClass.vectorTime[a] = Math.max(VectorClass.vectorTime[a],numericalTimestamp[a]);
	}
	VectorClass.vectorTime = VectorClass.increment(myProcessId);
    }
    
}