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
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    //key array
    static int[] keyArray;
    //Current request and flag
    static int currentRequestBeingServed = 0;
    static int outstandingRequest = 0;
    //Blocking queue to make the csEnter function blocking till the condition is met if not in first place
    static BlockingQueue<String> blockingQueue = new ArrayBlockingQueue<>(1);
    
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
                    int requestMade = Integer.parseInt(parseMessage[3] + parseMessage[1]);
                    if (outstandingRequest == 1) {
                        if (requestMade < currentRequestBeingServed) {
                            lamportClock++;
                            keyArray[Integer.parseInt(parseMessage[1])] = 0;
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                            //Include block for reverse request
                            lamportClock++;
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                        else if (requestMade == currentRequestBeingServed) {
                            if (Integer.parseInt(parseMessage[1]) < myProcessId) {
                                lamportClock++;
                                keyArray[Integer.parseInt(parseMessage[1])] = 0;
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                    sctpChannel[Integer.parseInt(parseMessage[1])].close();
                                } catch (IOException ex) {
                                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                                }
                                //Include block for reverse request
                                lamportClock++;
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
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
                        keyArray[Integer.parseInt(parseMessage[1])] = 0;
                        try {
                            byteBufferToNeighbor.clear();
                            initializeChannels();
                            sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                            String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock;
                            System.out.println("Message sent is : "+sendMessage);
                            MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                            byteBufferToNeighbor.put(sendMessage.getBytes());
                            byteBufferToNeighbor.flip();
                            sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
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
                        csExit();
                    }
                }
                else if (receiveMessage.contains("ReverseSend")) {
                    String[] parseMessage = receiveMessage.split("-");
                    lamportClock = Math.max(lamportClock,Integer.parseInt(parseMessage[3])) + 1;
                    int requestMade = Integer.parseInt(parseMessage[2]);
                    if (outstandingRequest == 1) {
                        if (requestMade < currentRequestBeingServed) {
                            lamportClock++;
                            keyArray[Integer.parseInt(parseMessage[1])] = 0;
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                            //Include block for reverse request
                            lamportClock++;
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock;
                                System.out.println("Message sent is : "+sendMessage);
                                MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                byteBufferToNeighbor.put(sendMessage.getBytes());
                                byteBufferToNeighbor.flip();
                                sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                sctpChannel[Integer.parseInt(parseMessage[1])].close();
                            } catch (IOException ex) {
                                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                        else if (requestMade == currentRequestBeingServed) {
                            if (Integer.parseInt(parseMessage[1]) < myProcessId) {
                                lamportClock++;
                                keyArray[Integer.parseInt(parseMessage[1])] = 0;
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                                    sctpChannel[Integer.parseInt(parseMessage[1])].close();
                                } catch (IOException ex) {
                                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                                }
                                //Include block for reverse request
                                lamportClock++;
                                try {
                                    byteBufferToNeighbor.clear();
                                    initializeChannels();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                                    String sendMessage = "ReverseSend from Process-" + myProcessId + "-" + currentRequestBeingServed + "-" + lamportClock;
                                    System.out.println("Message sent is : "+sendMessage);
                                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                                    byteBufferToNeighbor.put(sendMessage.getBytes());
                                    byteBufferToNeighbor.flip();
                                    sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
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
                        keyArray[Integer.parseInt(parseMessage[1])] = 0;
                        try {
                            byteBufferToNeighbor.clear();
                            initializeChannels();
                            sctpChannel[Integer.parseInt(parseMessage[1])].connect(socketAddress[Integer.parseInt(parseMessage[1])]);
                            String sendMessage = "Key from Process-" + myProcessId + "-" + requestMade + "-" + lamportClock;
                            System.out.println("Message sent is : "+sendMessage);
                            MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                            byteBufferToNeighbor.put(sendMessage.getBytes());
                            byteBufferToNeighbor.flip();
                            sctpChannel[Integer.parseInt(parseMessage[1])].send(byteBufferToNeighbor,messageInfoToNeighbor);
                            sctpChannel[Integer.parseInt(parseMessage[1])].close();
                        } catch (IOException ex) {
                            Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                        }
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
        //Use it for requesting critical section continuously in loop
        //Currently just sent one message request to each neighbor
        int numberOfRequest = 1;
        while (numberOfRequest <= maxNumberOfRequest) {
            try {
                Thread.sleep(interRequestDelay);
                csEnter(numberOfRequest);
            } catch (InterruptedException | IOException ex) {
                Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
            }
            numberOfRequest++;
        }
    }
    
    private void csEnter(int requestNumber) throws InterruptedException, IOException {
        mutex.acquire();
        lamportClock++;
        String requestString = Integer.toString(lamportClock) + Integer.toString(myProcessId);
        currentRequestBeingServed = Integer.parseInt(requestString);
        //queue.add(Integer.parseInt(requestString));
        outstandingRequest = 1;
        for (int k = 0 ; k < numberOfProcesses ; k++) {
            if (keyArray[k] == 0) {
                if (k != myProcessId) {
                    try {
                        byteBufferToNeighbor.clear();
                        initializeChannels();
                        sctpChannel[k].connect(socketAddress[k]);
                        String sendMessage = "Request from Process-" + myProcessId + "-" + requestNumber + "-" + lamportClock;
                        System.out.println("Message sent is : "+sendMessage);
                        MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                        byteBufferToNeighbor.put(sendMessage.getBytes());
                        byteBufferToNeighbor.flip();
                        sctpChannel[k].send(byteBufferToNeighbor,messageInfoToNeighbor);
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
    
    static private void enterCriticalSectionExecution() throws IOException {
        //Time for CS execution
       	System.out.println("0000000000000000000000000000000000000000");
        System.out.println("Inside critical section");
        System.out.println("0000000000000000000000000000000000000000");
	writer.write("0000000000000000000000000000000000000000");
	writer.write("Inside critical section");
	writer.write("0000000000000000000000000000000000000000");
    }
    
    static private void csExit() {
        if (!queue.isEmpty()) {
            lamportClock++;
            int loopSize = queue.size();
            for (int p = 0 ; p < loopSize ; p++) {
                int sendToken = queue.remove();
                try {
                    byteBufferToNeighbor.clear();
                    initializeChannels();
                    sctpChannel[sendToken%10].connect(socketAddress[sendToken%10]);
                    String sendMessage = "Key from Process-" + myProcessId + "-" + sendToken + "-" + lamportClock;
                    System.out.println("Message sent is : "+sendMessage);
                    MessageInfo messageInfoToNeighbor = MessageInfo.createOutgoing(null,0);
                    byteBufferToNeighbor.put(sendMessage.getBytes());
                    byteBufferToNeighbor.flip();
                    sctpChannel[sendToken%10].send(byteBufferToNeighbor,messageInfoToNeighbor);
                    sctpChannel[sendToken%10].close();
                } catch (IOException ex) {
                    Logger.getLogger(RoucairolCarvahloBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }
    
}
