package org.example;

import java.io.*;
import java.net.*;
import java.util.Timer;
import java.util.TimerTask;

public class SlaveNode {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 12346;

    public static void main(String[] args) {
        try{
            Socket socket = new Socket(MASTER_IP, MASTER_PORT);

             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

             // Get slave ID from master
             String slaveId = (String) in.readObject();
             System.out.println("Connected to master as: " + slaveId);

             // Start heartbeat
             new Timer().scheduleAtFixedRate(new TimerTask() {
                 public void run() {
                     try {
                         out.writeObject("HEARTBEAT");
                         out.flush();
                     } catch (IOException e) {
                         System.exit(0);
                     }
                 }
             }, 0, 3000);

            // Process tasks
            while (true) {
                String command = (String) in.readObject();
                if ("SCRIPT".equals(command)) {
                    receiveFile("received_script.py", in);
                }
                else if ("IMAGE".equals(command)) {
                    String imageName = (String) in.readObject();
                    receiveFile(imageName, in);
                    processImage(imageName, out);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void processImage(String imageName, ObjectOutputStream out) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "received_script.py", imageName);
            Process p = pb.start();
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                File resultFile = new File("bw_" + imageName);
                sendResult(resultFile, out);
            } else {
                out.writeObject("ERROR processing " + imageName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void receiveFile(String fileName, ObjectInputStream in) throws IOException, ClassNotFoundException {
        byte[] fileData = (byte[]) in.readObject();
        FileOutputStream fos = new FileOutputStream(fileName);
        fos.write(fileData);
        fos.close();
    }

    private static void sendResult(File resultFile, ObjectOutputStream out) throws IOException {
        out.writeObject(resultFile.getName());
        FileInputStream fis = new FileInputStream(resultFile);
        byte[] fileData = new byte[(int) resultFile.length()];
        fis.read(fileData);
        out.writeObject(fileData);
        fis.close();
        resultFile.delete();
    }
}