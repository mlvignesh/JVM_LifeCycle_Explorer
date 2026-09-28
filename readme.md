# JVM Lifecycle Explorer (JFR Visualizer)

A Java desktop visualization tool designed to analyze and display JVM lifecycle events using Java Flight Recorder (JFR) telemetry.

---

## Prerequisites

* **JDK:** Java Development Kit (JDK) **17 or higher** (Tested up to JDK 26)
* **OS:** Windows, macOS, or Linux

Verify your installed Java version by running:
```bash
java -version
```

---

## Project Structure

```text
.
├── bin/                    # Output directory for compiled .class files
└── src/
    └── main/
        └── java/
            └── jfr/        # Root package
                ├── content/
                ├── engine/
                ├── model/
                └── ui/     # Swing GUI components
```

---

## Building and Running

### Method 1: Cross-Platform Standard (Recommended)

This method works consistently across **Windows (Cmd/PowerShell)**, **macOS**, and **Linux** without shell-specific wildcard issues.

#### Step 1: Generate the source files list
* **Windows (Command Prompt / PowerShell):**
  ```cmd
  dir /s /b src\main\java\*.java > sources.txt
  ```
* **macOS / Linux / Git Bash:**
  ```bash
  find src/main/java -name "*.java" > sources.txt
  ```

#### Step 2: Compile the project
```bash
javac -d bin @sources.txt
```

#### Step 3: Launch the application
```bash
java -cp bin jfr.ui.JVMLifecycleexplorer
```

---

### Method 2: OS-Specific One-Liners

#### Windows (PowerShell)
```powershell
javac -d bin (Get-ChildItem -Path src/main/java -Recurse -Filter *.java).FullName
java -cp bin jfr.ui.JVMLifecycleexplorer
```

#### Linux / macOS / Git Bash
```bash
javac -d bin $(find src/main/java -name "*.java")
java -cp bin jfr.ui.JVMLifecycleexplorer
```

---

## Troubleshooting

* **JDK Version Warnings:**
  This project relies on `jdk.jfr` APIs available natively in JDK 17+. Ensure `JAVA_HOME` points to JDK 17 or newer.