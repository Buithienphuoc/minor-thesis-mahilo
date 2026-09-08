# Mahilo Multi-Agent System

## Prerequisites

Before getting started, ensure the following software is installed:

- Python 3.12
- Git
- OpenAI API Key

---

# Server Setup

## 1. Clone the Repository

```bash
git clone <repository_url>
cd <repository_name>
```

---

## 2. Activate the Existing Virtual Environment

This repository already contains a pre-configured virtual environment.

### Windows PowerShell

```powershell
.\.venv\Scripts\Activate.ps1
```

### Windows Command Prompt

```cmd
.venv\Scripts\activate.bat
```

### Linux / macOS

```bash
source .venv/bin/activate
```

---

## 3. Install Dependencies

```bash
pip install -r requirements.txt
```

---

## 4. Configure OpenAI API Key

Set your OpenAI API key as an environment variable.

### Windows (Permanent)

```powershell
:SetEnvironmentVariable(
    "OPENAI_API_KEY",
    "YOUR_OPENAI_API_KEY",
    "User"
)
```

Open a new terminal and verify:

```powershell
echo $env:OPENAI_API_KEY
```

### Linux / macOS

```bash
export OPENAI_API_KEY="YOUR_OPENAI_API_KEY"
```

Verify:

```bash
echo $OPENAI_API_KEY
```

---

## 5. Start the Mahilo Server

From the project root directory:

```bash
python run_server.py
```

Once started successfully, the server will register all configured agents and begin accepting WebSocket connections.

---

# Installing the Mahilo CLI

The Mahilo CLI allows you to connect to agents directly from a terminal.

## Install Mahilo

```bash
pip install mahilo
```

Verify installation:

```bash
mahilo --help
```

---

## If the `mahilo` Command Is Not Found

On some Windows systems, `mahilo.exe` may be installed into a directory that is not included in the system PATH.

Locate the executable:

```powershell
Get-ChildItem "$HOME\AppData\Roaming\Python" -Recurse -Filter mahilo.exe
```

Typical location:

```text
C:\Users\<username>\AppData\Roaming\Python\Python312\Scripts\mahilo.exe
```

Add the containing folder to your Windows PATH environment variable.

After updating PATH, open a new terminal and verify:

```bash
mahilo --help
```

---

# Connecting to Existing Agents

## Connect to MarketingAgent

```bash
mahilo connect \
  --url http://localhost:8000 \
  --agent-name MarketingAgent
```

---

## Connect to SalesAgent

```bash
mahilo connect \
  --url http://localhost:8000 \
  --agent-name SalesAgent
```

---

## Connect to a Remote Server

If the Mahilo server is running on another machine:

```bash
mahilo connect \
  --url http://<SERVER_IP>:8000 \
  --agent-name MarketingAgent
```

Example:

```bash
mahilo connect \
  --url http://10.247.169.84:8000 \
  --agent-name MarketingAgent
```

---

# Running Agents Directly from Source Code

In addition to using the Mahilo CLI, agents can also be started directly from the repository source code.

---

## 1. Create a Dedicated Virtual Environment for the Agent

Navigate to the agent directory:

```bash
cd agents
```

Create a virtual environment:

```bash
python -m venv .venv
```

Activate the environment:

### Windows PowerShell

```powershell
.\.venv\Scripts\Activate.ps1
```

### Linux / macOS

```bash
source .venv/bin/activate
```

---

## 2. Install Agent Dependencies

```bash
pip install -r requirements.txt
```

If your agent has a dedicated requirements file:

```bash
pip install -r agents/requirements.txt
```

---

## 3. Configure OpenAI API Key

Ensure the OpenAI API key is available in the environment:

```bash
python -c "import os; print(os.getenv('OPENAI_API_KEY'))"
```

The command should return your configured API key.

---

## 4. Start the Agent

Examples:

```bash
python marketing_agent.py
```

```bash
python sales_agent.py
```

```bash
python code_teacher_agent.py
```

Use the appropriate startup script based on the agent you wish to run.

---

## 5. Verify Agent Startup

Successful startup should display logs similar to:

```text
Registered agents:
- SalesAgent
- MarketingAgent
- CodeTeacherAgent
```

The agent should now be available for incoming WebSocket connections and inter-agent communication.

---

# Network Connectivity Test

To verify that a remote machine can reach the Mahilo server:

### Windows

```powershell
Test-NetConnection <SERVER_IP> -Port 8000
```

Example:

```powershell
Test-NetConnection 10.247.169.84 -Port 8000
```

Expected result:

```text
TcpTestSucceeded : True
```

---

# Common Issues

## OpenAI Authentication Error

Example:

```text
AuthenticationError: Incorrect API key provided
```

Verify:

```bash
python -c "import os; print(os.getenv('OPENAI_API_KEY'))"
```

Make sure the API key is valid and available in the current environment.

---

## Mahilo Command Not Found

Example:

```text
mahilo : The term 'mahilo' is not recognized...
```

Solution:

1. Locate `mahilo.exe`
2. Add its directory to the system PATH
3. Open a new terminal
4. Verify with:

```bash
mahilo --help
```

---

## Unable to Connect to the Server

Verify connectivity:

```powershell
Test-NetConnection <SERVER_IP> -Port 8000
```

Troubleshooting:

- Ensure the Mahilo server is running.
- Ensure port 8000 is open in the firewall.
- Ensure the server is bound to `0.0.0.0` instead of `127.0.0.1`.
- Verify that both machines are on the same network.

---

# System Architecture

```text
                 +------------------+
                 |   Mahilo Server  |
                 +------------------+
                          |
        +----------------+----------------+
        |                                 |
        v                                 v
   SalesAgent                      MarketingAgent
        |
        v
 CodeTeacherAgent
```

Agents can communicate with each other through the Mahilo AgentManager or through custom APIs implemented within the system.