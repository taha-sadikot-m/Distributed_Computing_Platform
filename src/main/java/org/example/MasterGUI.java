package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.UUID;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.*;
import shared.FilePacket;

public class MasterGUI extends JFrame {
    private JTextArea logArea = new JTextArea();
    private JButton startServerBtn = new JButton("Start Server");
    private JButton stopServerBtn = new JButton("Stop Server");
    private JButton uploadScriptBtn = new JButton("Upload Python Script");
    private JButton uploadImagesBtn = new JButton("Upload Images");
    private JButton downloadBtn = new JButton("Download Results");
    private JTextField portField = new JTextField("12345", 10);
    private JLabel statusLabel = new JLabel("Server not running");
    private ServerSocket serverSocket;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private ConcurrentHashMap<String, SlaveHandler> slaves = new ConcurrentHashMap<>();
    private File scriptFile;
    private List<File> imageFiles = new ArrayList<>();
    private File outputDir = new File("processed_results");
    private volatile boolean serverRunning = false;

    private DatabaseHandler dbHandler;
    private JButton showJobsBtn = new JButton("Show Job History");

    public MasterGUI() {

        try {
            dbHandler = new DatabaseHandler();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Database initialization failed!");
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        setTitle("Master Node Controller");
        setSize(800, 600);
        setupUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        outputDir.mkdir();
    }

    private void setupUI() {
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(new JLabel("Port:"));
        controlPanel.add(portField);
        controlPanel.add(startServerBtn);
        controlPanel.add(stopServerBtn);
        controlPanel.add(uploadScriptBtn);
        controlPanel.add(uploadImagesBtn);
        controlPanel.add(downloadBtn);

        stopServerBtn.setEnabled(false);
        uploadScriptBtn.setEnabled(false);
        uploadImagesBtn.setEnabled(false);
        downloadBtn.setEnabled(false);

        startServerBtn.addActionListener(this::startServer);
        stopServerBtn.addActionListener(this::stopServer);
        uploadScriptBtn.addActionListener(this::uploadScript);
        uploadImagesBtn.addActionListener(this::uploadImages);
        downloadBtn.addActionListener(e -> openOutputDirectory());

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel);

        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        setLayout(new BorderLayout());
        add(statusPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.SOUTH);

        controlPanel.add(showJobsBtn);
        showJobsBtn.addActionListener(e -> showJobHistory());
    }

    private void showJobHistory() {
        try {
            String sql = "SELECT " +
                    "j.job_id, " +
                    "strftime('%Y-%m-%d %H:%M:%S', j.start_time) AS start_time, " +
                    "j.status, " +
                    "j.num_images, " +
                    "(SELECT COUNT(*) FROM tasks t " +
                    " WHERE t.job_id = j.job_id AND t.status = 'COMPLETED') AS completed " +
                    "FROM jobs j " +
                    "ORDER BY j.start_time DESC";

            ResultSet rs = dbHandler.conn.createStatement().executeQuery(sql);

            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            while (rs.next()) {
                String status = rs.getString("status");
                int completed = rs.getInt("completed");
                int total = rs.getInt("num_images");

                // Add completion percentage
                double progress = total > 0 ? (completed * 100.0) / total : 0;

                sb.append(String.format(
                        "Job ID: %s\nStart: %s\nStatus: %s\nProgress: %d/%d (%.1f%%)\n\n",
                        rs.getString("job_id"),
                        rs.getString("start_time"),
                        status,
                        completed,
                        total,
                        progress
                ));
            }

            if (sb.length() == 0) {
                sb.append("No jobs found in history");
            }

            JOptionPane.showMessageDialog(this, sb.toString(), "Job History", JOptionPane.INFORMATION_MESSAGE);

        } catch (SQLException e) {
            log("Error loading job history: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Error loading job history:\n" + e.getMessage(),
                    "Database Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // Add proper cleanup
    @Override
    public void dispose() {
        try {
            if (dbHandler != null) dbHandler.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        super.dispose();
    }



    private void startServer(ActionEvent e) {
        new Thread(() -> {
            try {
                int port = validatePort(portField.getText());
                serverSocket = new ServerSocket(port);
                serverRunning = true;
                updateStatus("Server running at " + getLocalIP() + ":" + port);
                enableControls(true);
                log("Master server started on port " + port);

                while (serverRunning) {
                    Socket socket = serverSocket.accept();
                    SlaveHandler handler = new SlaveHandler(socket);
                    executor.execute(handler);
                    slaves.put(handler.getSlaveId(), handler);
                    log("New slave connected: " + handler.getSlaveId());
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                        "Invalid port number! Please enter between 1-65535");
            } catch (BindException ex) {
                JOptionPane.showMessageDialog(this,
                        "Port " + portField.getText() + " already in use!\n" +
                                "Please try another port number.");
            } catch (IOException ex) {
                if (serverRunning) {
                    log("Server error: " + ex.getMessage());
                }
            }
        }).start();
    }

    private void stopServer(ActionEvent e) {
        new Thread(() -> {
            try {

                serverRunning = false;
                // Send shutdown signal to slaves
                for (SlaveHandler slave : slaves.values()) {
                    try {
                        slave.out.writeObject("SHUTDOWN");
                        slave.out.flush();
                    } catch (IOException ex) {
                        // Already disconnected
                    }
                }

                // Close server socket
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }

                // Disconnect all slaves
                for (SlaveHandler slave : slaves.values()) {
                    slave.disconnect();
                }
                slaves.clear();

                // Shutdown executor
                executor.shutdownNow();

                updateStatus("Server stopped");
                enableControls(false);
                log("Server stopped. All slaves disconnected.");
            } catch (IOException ex) {
                log("Error stopping server: " + ex.getMessage());
            }
        }).start();
    }

    private int validatePort(String portStr) throws NumberFormatException {
        int port = Integer.parseInt(portStr);
        if (port < 1 || port > 65535) {
            throw new NumberFormatException("Port out of range");
        }
        return port;
    }

    private void uploadScript(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            scriptFile = fc.getSelectedFile();
            log("Python script uploaded: " + scriptFile.getName());
            uploadImagesBtn.setEnabled(serverRunning && scriptFile != null);
        }
    }

    private void uploadImages(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            imageFiles = Arrays.asList(fc.getSelectedFiles());
            log("Uploaded " + imageFiles.size() + " images");
            distributeTasks();
        }
    }

    private void distributeTasks() {
        if (slaves.isEmpty()) {
            log("No slaves available for task distribution");
            return;
        }

        TaskDistributor distributor = new TaskDistributor(slaves.values());
        new Thread(() -> {
            try {

                String jobId = dbHandler.createJob(imageFiles.size());
                File jobDir = new File(outputDir, jobId);
                jobDir.mkdir();

                // Create tasks in DB
                for (File image : imageFiles) {
                    dbHandler.createTask(jobId, image.getName());
                }


                // Send script to all slaves first
                FilePacket scriptPacket = new FilePacket(scriptFile.getName(), readFileBytes(scriptFile));
                for (SlaveHandler slave : slaves.values()) {
                    synchronized (slave.out) {
                        slave.out.writeObject("SCRIPT");
                        slave.out.writeObject(new FilePacket(scriptFile.getName(), readFileBytes(scriptFile)));
                        log("Sent script to " + slave.getSlaveId());
                    }
                }

                // Distribute images using round-robin
                for (File image : imageFiles) {
                    SlaveHandler slave = distributor.getNextSlave();
                    if (slave == null) continue;

                    synchronized (slave.out) {
                        slave.out.writeObject("IMAGE");
                        slave.out.writeObject(new FilePacket(image.getName(), readFileBytes(image)));
                        log("Distributed " + image.getName() + " to " + slave.getSlaveId());
                    }
                }
            } catch (SQLException ex) {
                log("Database error: " + ex.getMessage());
            }catch (IOException e) {
                log("Distribution error: " + e.getMessage());
            }
        }).start();
    }

    private byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            return buffer;
        }
    }


    class TaskDistributor {
        private final List<SlaveHandler> slaves;
        private int currentIndex = 0;

        public TaskDistributor(Collection<SlaveHandler> slaves) {
            this.slaves = new ArrayList<>(slaves);
        }

        public synchronized SlaveHandler getNextSlave() {
            if (slaves.isEmpty()) return null;
            currentIndex = (currentIndex + 1) % slaves.size();
            return slaves.get(currentIndex);
        }
    }

    private void openOutputDirectory() {
        try {
            Desktop.getDesktop().open(outputDir);
        } catch (IOException ex) {
            log("Error opening output directory: " + ex.getMessage());
        }
    }

    private void enableControls(boolean serverRunning) {
        SwingUtilities.invokeLater(() -> {
            portField.setEnabled(!serverRunning);
            startServerBtn.setEnabled(!serverRunning);
            stopServerBtn.setEnabled(serverRunning);
            uploadScriptBtn.setEnabled(serverRunning);
            uploadImagesBtn.setEnabled(serverRunning && scriptFile != null);
            downloadBtn.setEnabled(serverRunning);
        });
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append("[LOG] " + message + "\n"));
    }

    private String getLocalIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    class SlaveHandler implements Runnable {
        private final String slaveId = UUID.randomUUID().toString();
        private final Socket socket;
        private ObjectOutputStream out;
        private ObjectInputStream in;
        private long lastHeartbeat = System.currentTimeMillis();

        public SlaveHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new ObjectOutputStream(socket.getOutputStream());
            this.in = new ObjectInputStream(socket.getInputStream());
        }


        private void saveResult(FilePacket packet, String jobId) {
            File jobDir = new File(outputDir, jobId);
            File outputFile = new File(jobDir, packet.getFileName());

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(packet.getData());
                log("Saved processed file: " + outputFile.getAbsolutePath());

                // Update database
                dbHandler.updateTask(jobId,
                        packet.getFileName().replace("bw_", ""),
                        "COMPLETED",
                        packet.getFileName());

                // Verify output file exists before marking complete
                if (outputFile.exists()) {
                    dbHandler.updateTask(jobId,
                            packet.getFileName().replace("bw_", ""),
                            "COMPLETED",
                            packet.getFileName());
                } else {
                    dbHandler.updateTask(jobId,
                            packet.getFileName().replace("bw_", ""),
                            "FAILED",
                            "File not generated");
                }
            } catch (Exception ex) {
                log("Error saving file: " + ex.getMessage());
            }
        }






        public void sendTask(File script, File image) throws IOException {
            try {
                // Send script
                synchronized (out) {
                    out.writeObject(new FilePacket(script.getName(), readFileBytes(script)));
                    log("Sent script: " + script.getName());
                }

                // Send image
                synchronized (out) {
                    out.writeObject(new FilePacket(image.getName(), readFileBytes(image)));
                    log("Sent image: " + image.getName());
                }

            } catch (IOException e) {
                log("Failed to send task to " + slaveId + ": " + e.getMessage());
                throw e;
            }
        }

        private byte[] readFileBytes(File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[(int) file.length()];
                fis.read(buffer);
                return buffer;
            }
        }

        public String getSlaveId() {
            return slaveId;
        }

        public void disconnect() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ex) {
                log("Error disconnecting slave " + slaveId + ": " + ex.getMessage());
            }
        }

        public void run() {
            try {
                out.writeObject(slaveId);
                out.flush();

                while (true) {
                    Object received = in.readObject();
                    if (received instanceof FilePacket) {
                        saveResult((FilePacket) received);
                    } else if (received instanceof String) {
                        String msg = (String) received;
                        if ("HEARTBEAT".equals(msg)) {
                            lastHeartbeat = System.currentTimeMillis();
                            log("Heartbeat from " + slaveId);
                        }
                    }
                }
            } catch (Exception ex) {
                log("Slave disconnected: " + slaveId);
                slaves.remove(slaveId);
            }
        }

        public boolean isAlive() {
            return (System.currentTimeMillis() - lastHeartbeat) < 30000;
        }



        private void sendFile(File file) throws IOException {
            byte[] buffer = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(buffer);
            }
            out.writeObject(new FilePacket(file.getName(), buffer));
            out.flush();
        }

        private void saveResult(FilePacket packet) {
            try {
                File outputFile = new File(outputDir, packet.getFileName());
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(packet.getData());
                }
                log("Received processed file: " + outputFile.getName());
            } catch (IOException ex) {
                log("Error saving file from " + slaveId + ": " + ex.getMessage());
            }
        }
    }



    private void startSlaveMonitor() {
        new Timer().scheduleAtFixedRate(new TimerTask() {
            public void run() {
                Iterator<Map.Entry<String, SlaveHandler>> it = slaves.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, SlaveHandler> entry = it.next();
                    if (!entry.getValue().isAlive()) {
                        log("Slave timeout: " + entry.getKey());
                        it.remove();
                    }
                }
            }
        }, 0, 10000);
    }


    class DatabaseHandler {
        private static final String DB_URL = "jdbc:sqlite:master.db";
        private Connection conn;

        public DatabaseHandler() throws SQLException, ClassNotFoundException {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:master.db");
            createTables();
        }

        private void createTables() {
            try (Statement stmt = conn.createStatement()) {
                // Enable foreign keys and automatic timestamp
                stmt.execute("PRAGMA foreign_keys = ON");

                stmt.execute("CREATE TABLE IF NOT EXISTS jobs (" +
                        "job_id TEXT PRIMARY KEY, " +
                        "start_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "end_time DATETIME, " +
                        "status TEXT CHECK(status IN ('PROCESSING', 'COMPLETED', 'FAILED')) NOT NULL, " +
                        "num_images INTEGER NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS tasks (" +
                        "task_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "job_id TEXT NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE, " +
                        "image_name TEXT NOT NULL, " +
                        "status TEXT CHECK(status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')) NOT NULL, " +
                        "output_file TEXT, " +
                        "start_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "end_time DATETIME)");

            } catch (SQLException e) {
                log("Database initialization failed: " + e.getMessage());
            }
        }



        public String createJob(int numImages) throws SQLException {
            String jobId = UUID.randomUUID().toString();
            String sql = "INSERT INTO jobs(job_id, start_time, status, num_images) VALUES(?, datetime('now'), ?, ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, jobId);
                pstmt.setString(2, "PROCESSING");
                pstmt.setInt(3, numImages);
                pstmt.executeUpdate();
            }
            return jobId;
        }

        public void createTask(String jobId, String imageName) throws SQLException {
            String sql = "INSERT INTO tasks(job_id, image_name, status) VALUES(?, ?, ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, jobId);
                pstmt.setString(2, imageName);
                pstmt.setString(3, "PENDING");
                pstmt.executeUpdate();
            }
        }

        public void updateTask(String jobId, String imageName, String status, String outputFile) throws SQLException {
            String sql = "UPDATE tasks SET status = ?, output_file = ?, end_time = datetime('now') " +
                    "WHERE job_id = ? AND image_name = ?";

            System.out.println("Updating task: " + imageName + " to status: " + status);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, status);
                pstmt.setString(2, outputFile);
                pstmt.setString(3, jobId);
                pstmt.setString(4, imageName);
                int updated = pstmt.executeUpdate();
                System.out.println("Updated " + updated + " rows");
            }

            updateJobStatus(jobId);
        }

        public void updateJobStatus(String jobId) throws SQLException {
            System.out.println("Checking completion for job: " + jobId);

            String checkSql = "SELECT COUNT(*) AS remaining FROM tasks " +
                    "WHERE job_id = ? AND status != 'COMPLETED'";
            int remaining;

            try (PreparedStatement pstmt = conn.prepareStatement(checkSql)) {
                pstmt.setString(1, jobId);
                ResultSet rs = pstmt.executeQuery();
                remaining = rs.getInt("remaining");
                System.out.println("Remaining tasks: " + remaining);
            }

            if (remaining == 0) {
                System.out.println("Marking job as completed: " + jobId);
                String updateSql = "UPDATE jobs SET status = 'COMPLETED', end_time = datetime('now') " +
                        "WHERE job_id = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                    pstmt.setString(1, jobId);
                    int updated = pstmt.executeUpdate();
                    System.out.println("Job status updated: " + updated);
                }
            }
        }

        public void close() throws SQLException {
            if (conn != null) conn.close();
        }
    }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MasterGUI master = new MasterGUI();
            master.setVisible(true);
        });
    }
}