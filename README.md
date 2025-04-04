**Distributed Computing System Project** 🌐
=============================================

### **Project Description** 📄
This project enables users to process tasks across multiple slave nodes connected to a master node. The code supports multiple master and slave nodes, allowing for scalable and distributed computing. The master node shares status data with slave nodes, and users can upload script files and data to be processed. The files and data are communicated to slave nodes using a round-robin scheduling rule, processed, and then sent back to the master node for user download and utilization.

### **Code Directory Structure** 🗂️
```markdown
org.example
├── Master
│   ├── MasterGUI.java
│   └── slaveNode.java
├── Slave
│   ├── FilePacket.java
│   └── SlaveGUI.java
```

### **Execution Process** 🚀
1. **Run Master and Slave Nodes** 👉 Run multiple instances of `MasterGUI.java` and `SlaveGUI.java`.
2. **Configure Master Node** 📊
	* Enter the port number for the master node.
	* Start the server.
3. **Configure Slave Node** 📊
	* Enter the port number and IP address of the master node to connect.
4. **Upload Files and Data** 📁
	* Use the appropriate button to upload the Python file and data.
5. **Execute and Download** 💻
	* The data will be executed across slave nodes using round-robin scheduling.
	* Use the appropriate button to access the processed data.

**Example Use Case** 📊
------------------------

1. Run two instances of `MasterGUI.java` and three instances of `SlaveGUI.java`.
2. Configure the master nodes with port numbers 8080 and 8081.
3. Configure the slave nodes to connect to the master nodes using their respective IP addresses and port numbers.
4. Upload a Python script file and data to the master node.
5. Execute the data across the slave nodes and download the processed results.

**Note** 📝
--------

This project demonstrates a basic distributed computing system, and users can modify and extend the code to suit their specific requirements.
