/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
//package lamportbasicversion;

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
import java.util.Arrays;
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
public class LamportBasicVersion implements Runnable {

    /**
     * @param args the command line arguments
     * @throws java.io.FileNotFoundException
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
    //Blocking queue to make the csEnter function blocking till the condition is met if not in first place
    static BlockingQueue<String> blockingQueue = new ArrayBlockingQueue<>(1);
    //Condition status flag and current request served
    static int conditionL1 = 0;
    static int conditionL2 = 0;
    static int currentRequestBeingServed = 0;
    static int outstandingRequest = 0;
    //L1 and L2 condition status flag
    static int L1ConditionFlag = 0;
    static int L2ConditionFlag = 0;
    //Basic version condition array
    static int[] conditionArray;
    //For termination condition
    static int[] finishFlagArray;
    //File writer variables
    static String filename = "output-";
    static File file;
    static FileWriter writer;
    //File writer clock variables
    static String filenameClock = "outputClock-";
    static File fileClock;
    static FileWriter fw;
    static BufferedWriter bw;
    //Exponential Delay
    static ExponentialDistribution csExecutionExpoDelay;
    static ExponentialDistribution interRequestExpoDelay;
    static double timestampDelayId;
    static long timestampLongDelayId;
    double timestampDelayCs;
    long timestampLongDelayCs;
    //Response time variables
    static Timestamp timestamp1;
    static Timestamp timestamp2;
    static long responseTimeCalculation;
    //Message complexity variables
    static int totalMessageCount;
    
    public static void main(String[] args) throws FileNotFoundException, IOException, InterruptedException {
        totalMessageCount = 0;
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
        conditionArray = new int[numberOfProcesses];
        finishFlagArray = new int[numberOfProcesses];
        //Initializing vector class
        VectorClass.initialize(numberOfProcesses);
        //Fill the arrays with zero false value
        for (int o = 0 ; o < numberOfProcesses ; o++) {
            conditionArray[o] = 0;
            finishFlagArray[o] = 0;
        }
        // Write output to file
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
        new Thread(new LamportBasicVersion()).start();
        //while loop to receive all the requests and other messages
        while(true){
            try (SctpChannel sctpChannelFromClient = sctpServerChannel.accept()) {
                mutex.acquire();
                byteBufferFromNeighbor.clear();
                String receiveMessage;
                MessageInfo messageInfoFromNeighbor = sctpChannelFromClient.receive(byteBufferFromNeighbor,null,null);
                //System.out.println("Raw Message : " + messageInfoFromNeighbor);
                receiveMessage = byteToString(byteBufferFromNeighbor,messageInfoFromNeighbor);
                //write to file start
                writer.write("Received Message : " + receiveMessage);
                writer.write("\n");
                writer.flush();
                //write to file end
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
                    queue.add(requestMade);
                    //Send reply messages to that process for entering CS
                    lamportClock++;
                    //vector clock construction
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
                        if (k == Integer.parseInt(parseMessage[1])) {
                            try {
                                byteBufferToNeighbor.clear();
                                initializeChannels();
                                sctpChannel[k].connect(socketAddress[k]);
                                String sendMessage = "Reply from Process-" + myProcessId + "-" + Integer.toString(requestMade) + "-" + lamportClock + "-" + vectorClockConstruction;
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
                                Logger.getLogger(LamportBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                    }
                }
                else if (receiveMessage.contains("Reply")) {
                    conditionArray[myProcessId] = 1;
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
                    conditionArray[Integer.parseInt(parseMessage[1])] = 1;
                    int count = 0;
                    for (int v = 0 ; v < numberOfProcesses ; v++) {
                        if (conditionArray[v] == 1) {
                            count = count + 1;
                        }
                    }
                    if (count == numberOfProcesses) {
			L1ConditionFlag = 1;
			System.out.println("Inside L1");
                        blockingQueue.put("L1");
                        //Clearing condition array after receiving all REPLY
                        for (int z = 0 ; z < numberOfProcesses ; z++) {
                            conditionArray[z] = 0;
                        }
			if (L2ConditionFlag == 0 && outstandingRequest == 1) {
                            Integer[] queueArray = new Integer[queue.size()];
                            queue.toArray(queueArray);
                            Arrays.sort(queueArray);
                            if (queueArray[0] == currentRequestBeingServed) {
                                System.out.println("Inside L2");
                                L2ConditionFlag = 1;
                                blockingQueue.put("L2");
                            }
			}
                    }
                }
                else if (receiveMessage.contains("Release")) {
                    int present = 0;
                    int delete = 0;
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
		    if (queue.size() != 0) {
                    	Integer[] queueArray = new Integer[queue.size()];
                    	queue.toArray(queueArray);
                    	Arrays.sort(queueArray);
                    	for (int a = 0 ; a < queueArray.length ; a++) {
                            if (queueArray[a] == Integer.parseInt(parseMessage[2])) {
                            	present = 1;
                            	delete = a;
                            }
                    	}
                    	if (present == 1) {
                            for (int s = 0 ; s <= delete ; s++) {
                                queue.remove();
                            }
                        }
		    }
		    if (L2ConditionFlag == 0 && outstandingRequest == 1) {
		    	if (queue.size() != 0) {
                            Integer[] queueArray1 = new Integer[queue.size()];
                            queue.toArray(queueArray1);
                            Arrays.sort(queueArray1);
                            if (currentRequestBeingServed == queueArray1[0]) {
				L2ConditionFlag = 1;
				System.out.println("Inside L2");
                        	blockingQueue.put("L2");
                            }
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
                //logic for other messages
                //Print the queue to check
                System.out.println("********************************************************");
                for (Object item : queue) {
                    System.out.print(item);
                    System.out.print("\t");
                }
                System.out.println("********************************************************");
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
                    //currentRequestBeingServed = numberOfRequest;
                    csEnter(numberOfRequest);
                } catch (InterruptedException | IOException ex) {
                    Logger.getLogger(LamportBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
                numberOfRequest++;
            }
            sendFinish();
	    bw.write(String.valueOf(totalMessageCount));
	    bw.flush();
        } catch (InterruptedException | IOException ex) {
            Logger.getLogger(LamportBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
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
        queue.add(Integer.parseInt(requestString));
        //Timesatmp to record response time
        timestamp1 = new Timestamp(System.currentTimeMillis());
	//
	System.out.println("currentRequestServed : " + currentRequestBeingServed);
        outstandingRequest = 1;
        for (int k = 0 ; k < numberOfProcesses ; k++) {
            if (k != myProcessId) {
                try {
                    byteBufferToNeighbor.clear();
		    initializeChannels();
                    sctpChannel[k].connect(socketAddress[k]);
                    //String sendMessage = "Request from Process-" + myProcessId + "-" + requestNumber + "-" + lamportClock;
                    String sendMessage = "Request from Process-" + myProcessId + "-" + requestNumber + "-" + lamportClock + "-" + vectorClockConstruction;
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
                    Logger.getLogger(LamportBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        /* code for changing L1 and L2 status*/
        /* code for L1 */
/*        int[] conditionL1Array = new int[numberOfProcesses];
        for (int m = 0 ; m < conditionL1Array.length ; m++) {
            conditionL1Array[m] = 0;
        }
        conditionL1Array[myProcessId] = 1;
        Integer[] queueArray = new Integer[queue.size()];
        queue.toArray(queueArray);
        Arrays.sort(queueArray);
        for (int m = 0 ; m < queueArray.length ; m++) {
            if (queueArray[m] > Integer.parseInt(requestString)) {
                int source = queueArray[m]%10;
                conditionL1Array[source] = 1;
            }
        }
        int count = 0;
        for (int m = 0 ; m < conditionL1Array.length ; m++) {
            if (conditionL1Array[m] == 1) {
                count = count + 1;
            }
        }
        if (count == numberOfProcesses) {
            conditionL1 = 1;
        }
        /* code for L2 */
/*        if (queueArray[0] == Integer.parseInt(requestString)) {
            conditionL2 = 1;
        }                   */                    
        mutex.release();
        //logic for checking condition for L1 and L2 and if ok enter CS and neglect futher 
        //condition for L1 and L2 for that request
        //Last CS request enter flag should be given for neglect.
/*        if (conditionL1 == 1 && conditionL2 == 1) {
            outstandingRequest = 0;
            enterCriticalSectionExecution();
        }
        else {
            for (int n = 0 ; n < 2 ; n++) {
                String status = blockingQueue.take();
                if (status == "L1") {
                    conditionL1 = 1;
                }
                else if (status == "L2") {
                    conditionL2 = 1;
                }
            }
            if (conditionL1 == 1 && conditionL2 == 1) {
                outstandingRequest = 0;
                enterCriticalSectionExecution();
            }
        }                                                               */
        // Basic version wait for two condition from receive block
       /* for (int b = 0 ; b < 2 ; b++) {
            String statusString = blockingQueue.take();
            if ("L1".equals(statusString)) {
		mutex.acquire();
		System.out.println("Inside BQ L1");
                conditionL1 = 1;
		mutex.release();
            }
            else if ("L2".equals(statusString)) {
		mutex.acquire();
		System.out.println("Inside BQ L2");
                conditionL2 =1;
		mutex.release();
            }
            if (conditionL1 == 1 && conditionL2 == 1) {
                outstandingRequest = 0;
		conditionL1 = 0;
		conditionL2 = 0;
                mutex.acquire();
                enterCriticalSectionExecution();
            }
        }*/
	String statusString = blockingQueue.take();
	if (statusString == "L1") {
            conditionL1 = 1;
	}
	else if (statusString == "L2") {
            conditionL2 = 1;
	}
	String statusString1 = blockingQueue.take();
        if (statusString1 == "L1") {
            conditionL1 = 1;
        }
        else if (statusString1 == "L2") {
            conditionL2 = 1;
        }
	if (conditionL1 == 1 && conditionL2 == 1) {
            mutex.acquire();
            outstandingRequest = 0;
            conditionL1 = 0;
            conditionL2 = 0;
            L1ConditionFlag = 0;
            L2ConditionFlag = 0;
            currentRequestBeingServed = 0;
            enterCriticalSectionExecution();
        }
        timestamp2 = new Timestamp(System.currentTimeMillis());
        //Basic End
        csExit(requestNumber,requestString);
        mutex.release();
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

    private void enterCriticalSectionExecution() throws IOException, InterruptedException {
        //Time for CS execution
        timestampDelayCs = csExecutionExpoDelay.sample();
        timestampLongDelayCs = (long)timestampDelayCs;
        Thread.sleep(timestampLongDelayCs);
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

    private void csExit(int requestNumber, String requestString) throws IOException {
        //Response time
        responseTimeCalculation = timestamp2.getTime() - timestamp1.getTime();
        bw.write(String.valueOf(responseTimeCalculation));
        bw.write("\n");
	bw.flush();
        //Remove the request from queue
        queue.remove();
        //Getting the clock value from the rquest sent , the clock value may be different now
        lamportClock++;
        //vector clock construction
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
        //send the release message to the processes
        for (int k = 0 ; k < numberOfProcesses ; k++) {
            if (k != myProcessId) {
                try {
                    byteBufferToNeighbor.clear();
		    initializeChannels();
                    sctpChannel[k].connect(socketAddress[k]);
                    String sendMessage = "Release from Process-" + myProcessId + "-" + requestString + "-" + lamportClock + "-" + vectorClockConstruction;
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
                    Logger.getLogger(LamportBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        //clear the condition variables
        outstandingRequest = 0;
        conditionL1 = 0;
        conditionL2 = 0;
        L1ConditionFlag = 0;
        L2ConditionFlag = 0;
        currentRequestBeingServed = 0;
    }

    private void sendFinish() throws InterruptedException, IOException {
        mutex.acquire();
        finishFlagArray[myProcessId] = 1;
        lamportClock++;
        //vector clock construction
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
                    Logger.getLogger(LamportBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
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
                    Logger.getLogger(LamportBasicVersion.class.getName()).log(Level.SEVERE, null, ex);
                }
        }
        mutex.release();
    }
    
}
