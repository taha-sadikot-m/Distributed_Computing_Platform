package slave;

import shared.FilePacket;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class SlaveGUI extends JFrame {
    private JTextField ipField = new JTextField("localhost", 15);
    private JTextField portField = new JTextField("12345", 5);
    private JTextArea logArea = new JTextArea();
    private JButton connectBtn = new JButton("Connect");
    private JButton disconnectBtn = new JButton("Disconnect");
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String slaveId;
    private Timer heartbeatTimer;
    private String currentScriptName;

    private AtomicBoolean connected = new AtomicBoolean(false);


    public SlaveGUI() {
        setTitle("Slave Node Controller");
        setSize(600, 400);
        setupUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    private void setupUI() {
        JPanel connectionPanel = new JPanel(new FlowLayout());
        connectionPanel.add(new JLabel("Master IP:"));
        connectionPanel.add(ipField);
        connectionPanel.add(new JLabel("Port:"));
        connectionPanel.add(portField);
        connectionPanel.add(connectBtn);
        connectionPanel.add(disconnectBtn);

        connectBtn.addActionListener(e -> connectToMaster());
        disconnectBtn.addActionListener(e -> disconnect());
        disconnectBtn.setEnabled(false);

        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        setLayout(new BorderLayout());
        add(connectionPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void connectToMaster() {
        new Thread(() -> {
            try {
                Socket socket = new Socket(ipField.getText(), Integer.parseInt(portField.getText()));
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());
                connected.set(true);

                // Start heartbeat thread
                new Thread(this::sendHeartbeats).start();

                // Start processing thread
                new Thread(this::processTasks).start();

                // Get slave ID
                String receivedId = (String) in.readObject();
                log("Connected as: " + receivedId);

            } catch (Exception e) {
                log("Connection failed: " + e.getMessage());
                disconnect();
            }
        }).start();
    }

    private void sendHeartbeats() {
        try {
            while (connected.get()) {
                synchronized (out) {
                    out.writeObject("HEARTBEAT");
                    out.flush();
                }
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            log("Heartbeat error: " + e.getMessage());
            disconnect();
        }
    }

    private void startHeartbeat() {
        heartbeatTimer = new Timer(true);
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                try {
                    if (out != null) {
                        out.writeObject("HEARTBEAT");
                        out.flush();
                    }
                } catch (IOException e) {
                    log("Connection to master lost!");
                    disconnect();
                }
            }
        }, 0, 3000);
    }

    private void processTasks() {
        new Thread(() -> {
            try {
                while (connected.get()) {
                    String command = (String) in.readObject();

                    if ("SCRIPT".equals(command)) {
                        FilePacket scriptPacket = (FilePacket) in.readObject();
                        currentScriptName = scriptPacket.getFileName(); // Store received name
                        saveFile(currentScriptName, scriptPacket.getData());
                        log("Received script: " + currentScriptName);
                    }
                    else if ("IMAGE".equals(command)) {
                        FilePacket imagePacket = (FilePacket) in.readObject();
                        saveFile(imagePacket.getFileName(), imagePacket.getData());
                        log("Received image: " + imagePacket.getFileName());

                        // Process image
                        String result = processImage(imagePacket.getFileName());

                        // Send result
                        synchronized (out) {
                            out.writeObject("RESULT");
                            out.writeObject(new FilePacket(result, readResultFile(result)));
                            log("Sent result: " + result);
                        }
                    }
                }
            } catch (Exception e) {
                log("Task processing error: " + e.getMessage());
            }
        }).start();
    }


    private byte[] readResultFile(String fileName) {
        try {
            File file = new File(fileName);
            if (!file.exists()) return new byte[0];

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                return data;
            }
        } catch (Exception e) {
            log("Error reading result file: " + e.getMessage());
            return new byte[0];
        }
    }

    private void saveFile(String fileName, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            fos.write(data);
        }
    }

    private String processImage(String imageName) {
        try {
            log("Starting Python processing for: " + imageName);
            Process p = new ProcessBuilder("python", currentScriptName, imageName)
                    .redirectErrorStream(true)
                    .start();

            // Capture Python output
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = p.waitFor();
            log("Python process exited with code: " + exitCode);
            log("Python output:\n" + output);

            File resultFile = new File("bw_" + imageName);
            if (resultFile.exists()) {
                return resultFile.getName();
            }
            return "ERROR: Result file not created";
        } catch (Exception e) {
            log("Processing failed: " + e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    private void receiveFile(String fileName) throws IOException, ClassNotFoundException {
        byte[] fileData = (byte[]) in.readObject();
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            fos.write(fileData);
        }
    }

    private void sendResult(String resultFileName) {
        try {
            log("Attempting to send result: " + resultFileName);
            File resultFile = new File(resultFileName);

            if (resultFile.exists()) {
                try (FileInputStream fis = new FileInputStream(resultFile)) {
                    byte[] fileData = new byte[(int) resultFile.length()];
                    fis.read(fileData);
                    synchronized (out) {
                        out.writeObject(new FilePacket(resultFileName, fileData));
                        out.flush();
                    }
                    log("Successfully sent: " + resultFileName);
                }
                resultFile.delete();
            } else {
                synchronized (out) {
                    out.writeObject(new FilePacket(resultFileName, new byte[0]));
                    out.flush();
                }
                log("Sent error marker for: " + resultFileName);
            }
        } catch (Exception e) {
            log("Result sending failed: " + e.getMessage());
        }
    }

    private void disconnect() {
        connected.set(false);
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            log("Error disconnecting: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> {
            connectBtn.setEnabled(true);
            log("Disconnected from master");
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[Slave] " + message + "\n");
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SlaveGUI slaveGUI = new SlaveGUI();
            slaveGUI.setVisible(true);
        });
    }
}