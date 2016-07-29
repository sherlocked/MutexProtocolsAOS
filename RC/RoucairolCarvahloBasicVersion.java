/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
//package roucairolcarvahlobasicversion;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.math3.distribution.ExponentialDistribution;

/**
 *
 * @author Praveen MT
 */
public class RoucairolCarvahloBasicVersion implements Runnable {

    /**
     * @param args the command line arguments
     */
    //For parsing the file and storing the information
    static int myProcessId;
    static int numberOfProcesses;
    static int interRequestDelay;
    static int csExecutionTime;
    static int maxNumberOfRequest;
    static String[] machineNames;
    static int[] portNumbers;
    //For SCTP channels to neighbors
    static SocketAddress socketAddress[];
    static SctpChannel sctpChannel[];
    static int inetAddressSocketPort = 29999;
    //Buffer to send messages and receive messages
    static ByteBuffer byteBufferToNeighbor = ByteBuffer.allocate(1024);
    static ByteBuffer byteBufferFromNeighbor = ByteBuffer.allocate(1024);
    //Semaphore to access to blocking queue and clock
    static Semaphore mutex = new Semaphore(1);
    //Priority and comparator
    static Comparator<Integer> queueComparator = new IntegerComparator();
    static PriorityQueue<Integer> queue = new PriorityQueue<>(20,queueComparator);
    //Lamport's clock
    static int lamportClock = 0;
    //File writer variables
    static String filename = "output-";
    static File file;
    static FileWriter writer;
    //File writer clock variables
    static String filenameClock = "outputClock-";
    static File fileClock;
    static FileWriter fw;
    static BufferedWriter bw;
    //key array
    static int[] keyArray;
    //Current request and flag
    static int currentRequestBeingServed = 0;
    static int outstandingRequest = 0;
    //Blocking queue to make the csEnter function blocking till the condition is met if not in first place
    static BlockingQueue<String> blockingQueue = new ArrayBlockingQueue<>(1);
    //For termination condition
    static int[] finishFlagArray;
    //Message complexity variables
    static int totalMessageCount;
    //Exponential Delay
    static ExponentialDistribution csExecutionExpoDelay;
    static ExponentialDistribution interRequestExpoDelay;
    static double timestampDelayId;
    static long timestampLongDelayId;
    static double timestampDelayCs;
    static long timestampLongDelayCs;
    //Response time variables
    static Timestamp timestamp1;
    static Timestamp timestamp2;
    static long responseTimeCalculation;
    
    public static void main(String[] args) throws FileNotFoundException, IOException, InterruptedException {
        //For parsing the file and storing the information
        String line;
        String configurationFile = "configuration.txt";
        int lineCountInFile = 0;
        myProcessId = Integer.parseInt(args[0]);
        FileReader fileReader = new FileReader(configurationFile);
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        while((line=bufferedReader.readLine())!= null) {
            if ( (!(line.startsWith("#"))) && (!(line.isEmpty())) ) {
                lineCountInFile = lineCountInFile+1;
                String[] splitLine = line.split(" ");
                switch (lineCountInFile) {
                    case 1:
                        numberOfProcesses = Integer.parseInt(splitLine[0]);
                        interRequestDelay = Integer.parseInt(splitLine[1]);
                        csExecutionTime = Integer.parseInt(splitLine[2]);
                        maxNumberOfRequest = Integer.parseInt(splitLine[3]);
                        machineNames = new String[Integer.parseInt(splitLine[0])];
                        portNumbers = new int[Integer.parseInt(splitLine[0])];
                        break;
                    default:
                        machineNames[lineCountInFile-2] = splitLine[1];
                        portNumbers[lineCountInFile-2] = Integer.parseInt(splitLine[2]);
                        break;
                }
            }
        }
        //Initializing finish array
        finishFlagArray = new int[numberOfProcesses];
        //Initializing vector class
        VectorClass.initialize(numberOfProcesses);
        //Fill the arrays with zero false value
        for (int o = 0 ; o < numberOfProcesses ; o++) {
            finishFlagArray[o] = 0;
        }
        //Initializing key array and inserting values
        keyArray = new int[numberOfProcesses];
        for (int q = 0 ; q < numberOfProcesses ; q++) {
            if (q >= myProcessId) {
                keyArray[q] = 1;
            }
        }
        filename = filename + Integer.toString(myProcessId) + ".out";
        file = new File(filename);
        file.createNewFile();
        writer = new FileWriter(file);
        // Write clocks to file
        filenameClock = filenameClock + Integer.toString(myProcessId) + ".out";
        fileClock = new File(filenameClock);
        fileClock.createNewFile();
        //writerClock = new FileWriter(fileClock);
        fw = new FileWriter(fileClock);
	bw = new BufferedWriter(fw);
        //
        // Expo mean insert
        csExecutionExpoDelay = new ExponentialDistribution(csExecutionTime);
        interRequestExpoDelay = new ExponentialDistribution(interRequestDelay);
        //
        System.out.println("********************************************************");
        System.out.println("My process id : " + myProcessId);
        System.out.println("Number of processes : " + numberOfProcesses);
        System.out.println("Inter-request delay : " + interRequestDelay);
        System.out.println("Critical section execution time : " + csExecutionTime);
        System.out.println("Maximum number of request : " + maxNumberOfRequest);
        System.out.println("My process name : " + machineNames[myProcessId] + " My port number : " + portNumbers[myProcessId]);
        for (int i = 0 ; i < numberOfProcesses ; i++) {
            System.out.println("Process name : " + machineNames[i] + " Port number : " + portNumbers[i]);
        }
        System.out.println("********************************************************");
        for (int q = 0 ; q < numberOfProcesses ; q++) {
            System.out.println("KeyArray" + q + " - " + keyArray[q]);
        }
        System.out.println("********************************************************");
        //For hosting server localhost
        SctpServerChannel sctpServerChannel = SctpServerChannel.open();
        InetSocketAddress serverAddr = new InetSocketAddress(portNumbers[myProcessId]);
        sctpServerChannel.bind(serverAddr);
        System.out.println("********************************************************");
        System.out.println("Local server hosted");
        System.out.println("********************************************************");
        //For creating neighbor SCTP channels
        Thread.sleep(30000);
        socketAddress = new SocketAddress[numberOfProcesses];
        sctpChannel = new SctpChannel[numberOfProcesses];
        System.out.println("********************************************************");
        System.out.println("Neighbor channels created");
        System.out.println("********************************************************");
        //Thread spanned for generating critical section request
        new Thread(new RoucairolCarvahloBasicVersion()).start();
        while(true){
            try (SctpChannel sctpChannelFromClient = sctpServerChannel.accept()) {
                mutex.acquire();
                byteBufferFromNeighbor.clear();
                String receiveMessage;
                MessageInfo messageInfoFromNeighbor = sctpChannelFromClient.receive(byteBufferFromNeighbor,null,null);
                //System.out.println("Raw Message : " + messageInfoFromNeighbor);
                receiveMessage = byteToString(byteBufferFromNeighbor,messageInfoFromNeighbor);
                System.out.println("Received Message : " + receiveMessage);
                if (receiveMessage.contains("Request")) {
                    String[] parseMessage = receiveMessage.split("-");
                    lamportClock = Math.max(lamportClock,Integer.parseInt(parseMessage[3])) + 1;
                    //vector clock update
                    String[] stringNumericalTimestamp = parseMessage[4].split(",");
                    int[] numericalTimestamp = new int[stringNumericalTimestamp.length];
                    for (int d = 0 ; d < stringNumericalTimestamp.length ; d++) {
                        numericalTimestamp[d] = Integer.parseInt(stringNumericalTimestamp[d]);
                    }
                    VectorClass.update(myProcessId,numericalTimestamp);
                    //
                    int requestMade = Integer.parseInt(parseMessage[3] + parseMessage[1]);
                    if (outstandingRequest == 1) {
                        if (requestMade < currentRequestBeingServed) {
                            lamportClock++;
                            //Newly inserted for vector timesatmp
                            int[] vector = VectorClass.increment(myProcessId);
                            String vectorClockConstruction = "";
                            for (int g = 0 ; g < vector.length ; g++) {
                                if (g == 0) {
                                    vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
                                }
                                else {
                                    vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
                                }
                            }
                            //
                            keyArray[Integer.parseInt(parseMessage[1])] = 0;
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock + "-" + vectorClockConstruction;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                totalMessageCount++;
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                            //Include block for reverse request
                            lamportClock++;
                            //Newly inserted for vector timesatmp
                            int[] vector1 = VectorClass.increment(myProcessId);
                            String vectorClockConstruction1 = "";
                            for (int g = 0 ; g < vector1.length ; g++) {
                                if (g == 0) {
                                    vectorClockConstruction1 = vectorClockConstruction1 + Integer.toString(vector1[g]);
                                }
                                else {
                                    vectorClockConstruction1 = vectorClockConstruction1 + "," + Integer.toString(vector1[g]);
                                }
                            }
                            //
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock + "-" + vectorClockConstruction1;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                totalMessageCount++;
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                        else if (requestMade == currentRequestBeingServed) {
                            if (Integer.parseInt(parseMessage[1]) < myProcessId) {
                                lamportClock++;
                                //Newly inserted for vector timesatmp
                                int[] vector = VectorClass.increment(myProcessId);
                                String vectorClockConstruction = "";
                                for (int g = 0 ; g < vector.length ; g++) {
                                    if (g == 0) {
                                        vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
                                    }
                                    else {
                                        vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
                                    }
                                }
                                //
                                keyArray[Integer.parseInt(parseMessage[1])] = 0;
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock + "-" + vectorClockConstruction;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                    totalMessageCount++;
                                    sctpChannel[Integer.parseInt(parseMessage[1])].close();
                                } catch (IOException ex) {
                                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                                }
                                //Include block for reverse request
                                lamportClock++;
                                //Newly inserted for vector timesatmp
                                int[] vector1 = VectorClass.increment(myProcessId);
                                String vectorClockConstruction1 = "";
                                for (int g = 0 ; g < vector1.length ; g++) {
                                    if (g == 0) {
                                        vectorClockConstruction1 = vectorClockConstruction1 + Integer.toString(vector1[g]);
                                    }
                                    else {
                                        vectorClockConstruction1 = vectorClockConstruction1 + "," + Integer.toString(vector1[g]);
                                    }
                                }
                                //
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock + "-" + vectorClockConstruction1;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                    totalMessageCount++;
                                    sctpChannel[Integer.parseInt(parseMessage[1])].close();
                                } catch (IOException ex) {
                                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                                }
                            }
                            else if (myProcessId < Integer.parseInt(parseMessage[1])) {
                                queue.add(requestMade);
                            }
                        }
                        else if (requestMade > currentRequestBeingServed) {
                            queue.add(requestMade);
                        }
                    }
                    else if (outstandingRequest == 0) {
                        lamportClock++;
                        //Newly inserted for vector timesatmp
                        int[] vector = VectorClass.increment(myProcessId);
                        String vectorClockConstruction = "";
                        for (int g = 0 ; g < vector.length ; g++) {
                            if (g == 0) {
                                vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
                            }
                            else {
                                vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
                            }
                        }
                        //
                        keyArray[Integer.parseInt(parseMessage[1])] = 0;
                        try {
                            byteBufferToNeighbor.clear();
                            initializeChannels();
                            sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                            String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock + "-" + vectorClockConstruction;
                            System.out.println("Message sent is : "+sendMessage);
                            MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                            byteBufferToNeighbor.put(sendMessage.getBytes());
                            byteBufferToNeighbor.flip();
                            sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                            totalMessageCount++;
                            sctpChannel[Integer.parseInt(parseMessage[1])].close();
                        } catch (IOException ex) {
                            Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                }
                else if (receiveMessage.contains("Key")) {
                    //receive check condition execute critical section block
                    String[] parseMessage = receiveMessage.split("-");
                    lamportClock = Math.max(lamportClock,Integer.parseInt(parseMessage[3])) + 1;
                    //vector clock update
                    String[] stringNumericalTimestamp = parseMessage[4].split(",");
                    int[] numericalTimestamp = new int[stringNumericalTimestamp.length];
                    for (int d = 0 ; d < stringNumericalTimestamp.length ; d++) {
                        numericalTimestamp[d] = Integer.parseInt(stringNumericalTimestamp[d]);
                    }
                    VectorClass.update(myProcessId,numericalTimestamp);
                    //
                    keyArray[Integer.parseInt(parseMessage[1])] = 1;
                    int countOnes = 0;
                    for (int y = 0 ; y < numberOfProcesses ; y++) {
                        if (keyArray[y] == 1) {
                            countOnes = countOnes + 1;
                        }
                    }
                    if (countOnes == numberOfProcesses) {
                        outstandingRequest = 0;
                        currentRequestBeingServed = 0;
                        enterCriticalSectionExecution();
                        timestamp2 = new Timestamp(System.currentTimeMillis());
                        csExit();
                    }
                }
                else if (receiveMessage.contains("ReverseSend")) {
                    String[] parseMessage = receiveMessage.split("-");
                    lamportClock = Math.max(lamportClock,Integer.parseInt(parseMessage[3])) + 1;
                    //vector clock update
                    String[] stringNumericalTimestamp = parseMessage[4].split(",");
                    int[] numericalTimestamp = new int[stringNumericalTimestamp.length];
                    for (int d = 0 ; d < stringNumericalTimestamp.length ; d++) {
                        numericalTimestamp[d] = Integer.parseInt(stringNumericalTimestamp[d]);
                    }
                    VectorClass.update(myProcessId,numericalTimestamp);
                    //
                    int requestMade = Integer.parseInt(parseMessage[2]);
                    if (outstandingRequest == 1) {
                        if (requestMade < currentRequestBeingServed) {
                            lamportClock++;
                            //Newly inserted for vector timesatmp
                            int[] vector = VectorClass.increment(myProcessId);
                            String vectorClockConstruction = "";
                            for (int g = 0 ; g < vector.length ; g++) {
                                if (g == 0) {
                                    vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
                                }
                                else {
                                    vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
                                }
                            }
                            //
                            keyArray[Integer.parseInt(parseMessage[1])] = 0;
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock + "-" + vectorClockConstruction;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                totalMessageCount++;
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                            //Include block for reverse request
                            lamportClock++;
                            //Newly inserted for vector timesatmp
                            int[] vector1 = VectorClass.increment(myProcessId);
                            String vectorClockConstruction1 = "";
                            for (int g = 0 ; g < vector1.length ; g++) {
                                if (g == 0) {
                                    vectorClockConstruction1 = vectorClockConstruction1 + Integer.toString(vector1[g]);
                                }
                                else {
                                    vectorClockConstruction1 = vectorClockConstruction1 + "," + Integer.toString(vector1[g]);
                                }
                            }
                            //
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock + "-" + vectorClockConstruction1;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                totalMessageCount++;
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                        else if (requestMade == currentRequestBeingServed) {
                            if (Integer.parseInt(parseMessage[1]) < myProcessId) {
                                lamportClock++;
                                //Newly inserted for vector timesatmp
                                int[] vector = VectorClass.increment(myProcessId);
                                String vectorClockConstruction = "";
                                for (int g = 0 ; g < vector.length ; g++) {
                                    if (g == 0) {
                                        vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
                                    }
                                    else {
                                        vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
                                    }
                                }
                                //
                                keyArray[Integer.parseInt(parseMessage[1])] = 0;
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock + "-" + vectorClockConstruction;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                    totalMessageCount++;
                                    sctpChannel[Integer.parseInt(parseMessage[1])].close();
                                } catch (IOException ex) {
                                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                                }
                                //Include block for reverse request
                                lamportClock++;
                                //Newly inserted for vector timesatmp
                                int[] vector1 = VectorClass.increment(myProcessId);
                                String vectorClockConstruction1 = "";
                                for (int g = 0 ; g < vector1.length ; g++) {
                                    if (g == 0) {
                                        vectorClockConstruction1 = vectorClockConstruction1 + Integer.toString(vector1[g]);
                                    }
                                    else {
                                        vectorClockConstruction1 = vectorClockConstruction1 + "," + Integer.toString(vector1[g]);
                                    }
                                }
                                //
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock + "-" + vectorClockConstruction1;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                    totalMessageCount++;
                                    sctpChannel[Integer.parseInt(parseMessage[1])].close();
                                } catch (IOException ex) {
                                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                                }
                            }
                            else if (myProcessId < Integer.parseInt(parseMessage[1])) {
                                queue.add(requestMade);
                            }
                        }
                        else if (requestMade > currentRequestBeingServed) {
                            queue.add(requestMade);
                        }
                    }
                    else if (outstandingRequest == 0) {
                        lamportClock++;
                        //Newly inserted for vector timesatmp
                        int[] vector = VectorClass.increment(myProcessId);
                        String vectorClockConstruction = "";
                        for (int g = 0 ; g < vector.length ; g++) {
                            if (g == 0) {
                                vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
                            }
                            else {
                                vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
                            }
                        }
                        //
                        keyArray[Integer.parseInt(parseMessage[1])] = 0;
                        try {
                            byteBufferToNeighbor.clear();
                            initializeChannels();
                            sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                            String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock + "-" + vectorClockConstruction;
                            System.out.println("Message sent is : "+sendMessage);
                            MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                            byteBufferToNeighbor.put(sendMessage.getBytes());
                            byteBufferToNeighbor.flip();
                            sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                            totalMessageCount++;
                            sctpChannel[Integer.parseInt(parseMessage[1])].close();
                        } catch (IOException ex) {
                            Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                }
                else if (receiveMessage.contains("Finish")) {
                    String[] parseMessage = receiveMessage.split("-");
                    lamportClock = Math.max(lamportClock,Integer.parseInt(parseMessage[3])) + 1;
                    //vector clock update
                    String[] stringNumericalTimestamp = parseMessage[4].split(",");
                    int[] numericalTimestamp = new int[stringNumericalTimestamp.length];
                    for (int d = 0 ; d < stringNumericalTimestamp.length ; d++) {
                        numericalTimestamp[d] = Integer.parseInt(stringNumericalTimestamp[d]);
                    }
                    VectorClass.update(myProcessId,numericalTimestamp);
                    //
                    finishFlagArray[Integer.parseInt(parseMessage[1])] = 1;
                    int count = 0;
                    for (int v = 0 ; v < numberOfProcesses ; v++) {
                        if (finishFlagArray[v] == 1) {
                            count = count + 1;
                        }
                    }
                    if (count == numberOfProcesses) {
                        break;
                    }
                }
            }
            mutex.release();
        }
    }
    
    private static String byteToString(ByteBuffer byteBufferFromNeighbor, MessageInfo messageInfoFromNeighbor) {
        byteBufferFromNeighbor.position(0);
        byteBufferFromNeighbor.limit(messageInfoFromNeighbor.bytes());
        byte[] bufArr = new byte[byteBufferFromNeighbor.remaining()];
        byteBufferFromNeighbor.get(bufArr);
        return new String(bufArr);
    }

    @Override
    public void run() {
        try {
            //Use it for requesting critical section continuously in loop
            //Currently just sent one message request to each neighbor
            int numberOfRequest = 1;
            while (numberOfRequest <= maxNumberOfRequest) {
                try {
                    timestampDelayId = interRequestExpoDelay.sample();
                    timestampLongDelayId = (long)timestampDelayId;
                    Thread.sleep(timestampLongDelayId);
                    csEnter(numberOfRequest);
                } catch (InterruptedException | IOException ex) {
                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
                numberOfRequest++;
            }
            sendFinish();
            bw.write(String.valueOf(totalMessageCount));
	    bw.flush();
        } catch (InterruptedException | IOException ex) {
            Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private void csEnter(int requestNumber) throws InterruptedException, IOException {
        mutex.acquire();
        lamportClock++;
        //Newly inserted for vector timesatmp
        int[] vector = VectorClass.increment(myProcessId);
        String vectorClockConstruction = "";
        for (int g = 0 ; g < vector.length ; g++) {
            if (g == 0) {
                vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
            }
            else {
                vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
            }
        }
        //
        String requestString = Integer.toString(lamportClock) + Integer.toString(myProcessId);
        currentRequestBeingServed = Integer.parseInt(requestString);
        //queue.add(Integer.parseInt(requestString));
        timestamp1 = new Timestamp(System.currentTimeMillis());
        outstandingRequest = 1;
        for (int k = 0 ; k < numberOfProcesses ; k++) {
            if (keyArray[k] == 0) {
                if (k != myProcessId) {
                    try {
                        byteBufferToNeighbor.clear();
                        initializeChannels();
                        sctpChannel[k].connect(socketAddress[k]);
                        String sendMessage = "Request from Process-" + myProcessId + "-" + requestNumber + "-" + lamportClock + "-" + vectorClockConstruction;
                        System.out.println("Message sent is : "+sendMessage);
                        MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                        byteBufferToNeighbor.put(sendMessage.getBytes());
                        byteBufferToNeighbor.flip();
                        sctpChannel[k].send(byteBufferToNeighbor,messageInfoToNeighbor);
                        totalMessageCount++;
                        sctpChannel[k].close();
                    } catch (IOException ex) {
                        Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        }
        //write code to check array condition go into the code
        int countOnes = 0;
        for (int y = 0 ; y < numberOfProcesses ; y++) {
            if (keyArray[y] == 1) {
                countOnes = countOnes + 1;
            }
        }
        if (countOnes == numberOfProcesses) {
            outstandingRequest = 0;
            currentRequestBeingServed = 0;
            enterCriticalSectionExecution();
            timestamp2 = new Timestamp(System.currentTimeMillis());
            csExit();
            mutex.release();
        }
        else {
            mutex.release();
        }
        /*else {
            mutex.release();
            //Condition check if all condition satified may be blocking queue
            String status = blockingQueue.take();
            mutex.acquire();
            outstandingRequest = 0;
            currentRequestBeingServed = 0;
            enterCriticalSectionExecution();
            csExit(requestNumber,"requestString");
            mutex.release();
        }*/
    }
    
    static public void initializeChannels() throws IOException {
	for (int j = 0 ; j < numberOfProcesses ; j++) {
            if (j != myProcessId) {
                socketAddress[j] = new InetSocketAddress(machineNames[j],portNumbers[j]);
                sctpChannel[j] = SctpChannel.open();
                sctpChannel[j].bind(new InetSocketAddress(++inetAddressSocketPort));
            }
        }
    }
    
    static private void enterCriticalSectionExecution() throws IOException, InterruptedException {
        //Time for CS execution
        timestampDelayCs = csExecutionExpoDelay.sample();
        timestampLongDelayCs = (long)timestampDelayCs;
        Thread.sleep(timestampLongDelayCs);
        //Time for CS execution
       	System.out.println("0000000000000000000000000000000000000000");
        System.out.println("Inside critical section");
        System.out.println("0000000000000000000000000000000000000000");
	writer.write("0000000000000000000000000000000000000000");
	writer.write("Inside critical section");
	writer.write("0000000000000000000000000000000000000000");
        int[] vectorTimestamp = VectorClass.getVectorTime();
        for (int y = 0 ; y < vectorTimestamp.length ; y++) {
            if (y == 0) {
                bw.write(String.valueOf(vectorTimestamp[y]));
		bw.flush();
            }
            else {
                bw.write(" ");
                bw.write(String.valueOf(vectorTimestamp[y]));
		bw.flush();
            }
        }
        bw.write("\n");
	bw.flush();
        bw.write(String.valueOf(timestampLongDelayId));
        bw.write(" ");
        bw.write(String.valueOf(timestampLongDelayCs));
        bw.write(" ");
    }
    
    static private void csExit() throws IOException {
        responseTimeCalculation = timestamp2.getTime() - timestamp1.getTime();
        bw.write(String.valueOf(responseTimeCalculation));
        bw.write("\n");
	bw.flush();
        if (!queue.isEmpty()) {
            lamportClock++;
            //Newly inserted for vector timesatmp
            int[] vector = VectorClass.increment(myProcessId);
            String vectorClockConstruction = "";
            for (int g = 0 ; g < vector.length ; g++) {
                if (g == 0) {
                    vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
                }
                else {
                    vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
                }
            }
            //
            int loopSize = queue.size();
            for (int p = 0 ; p < loopSize ; p++) {
                int sendToken = queue.remove();
                try {
                    byteBufferToNeighbor.clear();
                    initializeChannels();
                    sctpChannel[sendToken%10].connect(socketAddress[sendToken%10]);
                    String sendMessage = "Key from Process-" + myProcessId + "-" + sendToken + "-" + lamportClock + "-" + vectorClockConstruction;
                    System.out.println("Message sent is : "+sendMessage);
                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                    byteBufferToNeighbor.put(sendMessage.getBytes());
                    byteBufferToNeighbor.flip();
                    sctpChannel[sendToken%10].send(byteBufferToNeighbor,messageInfoToNeighbor);
                    totalMessageCount++;
                    sctpChannel[sendToken%10].close();
                } catch (IOException ex) {
                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    private void sendFinish() throws InterruptedException, IOException {
        mutex.acquire();
        finishFlagArray[myProcessId] = 1;
        lamportClock++;
        //Newly inserted for vector timesatmp
        int[] vector = VectorClass.increment(myProcessId);
        String vectorClockConstruction = "";
        for (int g = 0 ; g < vector.length ; g++) {
            if (g == 0) {
                vectorClockConstruction = vectorClockConstruction + Integer.toString(vector[g]);
            }
            else {
                vectorClockConstruction = vectorClockConstruction + "," + Integer.toString(vector[g]);
            }
        }
        //
        for (int k = 0 ; k < numberOfProcesses ; k++) {
            if (k != myProcessId) {
                try {
                    byteBufferToNeighbor.clear();
		    initializeChannels();
                    sctpChannel[k].connect(socketAddress[k]);
                    String sendMessage = "Finish from Process-" + myProcessId + "-" + "FinishMessage" + "-" + lamportClock + "-" + vectorClockConstruction;
                    System.out.println("Message sent is : "+sendMessage);
                    //write to file start
                    writer.write("Message sent is : "+sendMessage);
                    writer.write("\n");
                    writer.flush();
                    //write to file end
                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                    byteBufferToNeighbor.put(sendMessage.getBytes());
                    byteBufferToNeighbor.flip();
                    sctpChannel[k].send(byteBufferToNeighbor,messageInfoToNeighbor);
                    totalMessageCount++;
                    sctpChannel[k].close();
                } catch (IOException ex) {
                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        int count = 0;
        for (int v = 0 ; v < numberOfProcesses ; v++) {
            if (finishFlagArray[v] == 1) {
                count = count + 1;
            }
        }
        if (count == numberOfProcesses) {
            //Send message to self to terminate (Inter thread communcation)
            socketAddress[myProcessId] = new InetSocketAddress(machineNames[myProcessId],portNumbers[myProcessId]);
            sctpChannel[myProcessId] = SctpChannel.open();
            sctpChannel[myProcessId].bind(new InetSocketAddress(++inetAddressSocketPort));
            try {
                    byteBufferToNeighbor.clear();
		    //initializeChannels();
                    sctpChannel[myProcessId].connect(socketAddress[myProcessId]);
                    String sendMessage = "Finish from Process-" + myProcessId + "-" + "FinishMessage" + "-" + lamportClock + "-" + vectorClockConstruction;
                    System.out.println("Message sent is : "+sendMessage);
                    //write to file start
                    writer.write("Message sent is : "+sendMessage);
                    writer.write("\n");
                    writer.flush();
                    //write to file end
                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                    byteBufferToNeighbor.put(sendMessage.getBytes());
                    byteBufferToNeighbor.flip();
                    sctpChannel[myProcessId].send(byteBufferToNeighbor,messageInfoToNeighbor);
                    totalMessageCount++;
                    sctpChannel[myProcessId].close();
                } catch (IOException ex) {
                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
        }
        mutex.release();
    }
    
}
