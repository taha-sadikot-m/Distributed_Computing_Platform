# Distributed Image Processing System 🖥️🖼️

A distributed system for parallel image processing using Java Swing, Python, and SQLite. Master node distributes tasks to slave nodes, processes images using Python scripts, and tracks jobs in a database.

![System Architecture](docs/architecture.png) <!-- Add architecture diagram if available -->

## Features ✨

- **Master-Slave Architecture**: Central master node coordinates multiple slave nodes
- **GUI Dashboard**: Real-time monitoring of nodes and jobs
- **Python Script Execution**: Run custom image processing scripts (e.g., grayscale conversion)
- **Job Management**: 
  - Automatic task distribution
  - Progress tracking
  - Historical job analytics
- **Database Integration**: SQLite storage for job metadata and task statuses
- **Fault Tolerance**: Automatic slave node detection and task reassignment



## Prerequisites 📋

- Java 17+
- Python 3.8+
- Maven 3.6+
- SQLite JDBC Driver (auto-installed via Maven)

## Installation ⚙️

```bash
# Clone repository
git clone https://github.com/yourusername/distributed-image-processing.git
cd distributed-image-processing

# Build project
mvn clean install

# Verify SQLite driver in target/dependencies
