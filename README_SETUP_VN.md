# Mahilo Multi-Agent System

## Prerequisites

### Server Requirements

- Python 3.12
- Git
- OpenAI API Key

---

# 1. Clone Repository

```bash
git clone <repository_url>
cd <repository_name>
```

---

# 2. Setup Python Virtual Environment

Tạo và kích hoạt virtual environment bằng Python 3.12:

```bash
python -m venv .venv
```

### Windows PowerShell

```powershell
.\.venv\Scripts\Activate.ps1
```

### Windows CMD

```cmd
.venv\Scripts\activate.bat
```

### Linux/macOS

```bash
source .venv/bin/activate
```

---

# 3. Install Dependencies

```bash
pip install -r requirements.txt
```

---

# 4. Configure OpenAI API Key

## Windows (Permanent)

PowerShell:

```powershell
:SetEnvironmentVariable(
    "OPENAI_API_KEY",
    "YOUR_OPENAI_API_KEY",
    "User"
)
```

Mở lại terminal và kiểm tra:

```powershell
echo $env:OPENAI_API_KEY
```

## Linux/macOS

```bash
export OPENAI_API_KEY="YOUR_OPENAI_API_KEY"
```

---

# 5. Start Mahilo Server

Từ thư mục root của project:

```bash
python run_server.py
```

Khi server khởi động thành công, bạn sẽ thấy các agent được đăng ký và WebSocket server bắt đầu lắng nghe kết nối.

---

# Running Mahilo CLI

Mahilo CLI được dùng để kết nối tới từng Agent hoặc tương tác với Agent từ terminal.

## Install Mahilo CLI

```bash
pip install mahilo
```

Sau khi cài đặt:

```bash
mahilo --help
```

Nếu hiển thị help menu nghĩa là cài đặt thành công.

---

## If `mahilo` Command Not Found

Một số hệ thống Windows không tự động thêm thư mục chứa `mahilo.exe` vào biến môi trường PATH.

Tìm vị trí của `mahilo.exe`:

```powershell
Get-ChildItem "$HOME\AppData\Roaming\Python" -Recurse -Filter mahilo.exe
```

Ví dụ:

```text
C:\Users\<username>\AppData\Roaming\Python\Python312\Scripts\mahilo.exe
```

Thêm thư mục này vào PATH của Windows.

Sau khi thêm PATH:

```bash
mahilo --help
```

phải hoạt động bình thường.

---

# Connect To Existing Agents

Ví dụ kết nối tới MarketingAgent:

```bash
mahilo connect \
  --url http://localhost:8000 \
  --agent-name MarketingAgent
```

Ví dụ kết nối tới SalesAgent:

```bash
mahilo connect \
  --url http://localhost:8000 \
  --agent-name SalesAgent
```

Nếu server nằm ở máy khác:

```bash
mahilo connect \
  --url http://<SERVER_IP>:8000 \
  --agent-name MarketingAgent
```

Ví dụ:

```bash
mahilo connect \
  --url http://10.247.169.84:8000 \
  --agent-name MarketingAgent
```

---

# Running Agents From Source Code

Ngoài Mahilo CLI, các Agent cũng có thể được chạy trực tiếp từ repository.

---

## 1. Create Agent Virtual Environment

Di chuyển tới thư mục Agent.

Ví dụ:

```bash
cd agents
```

Tạo virtual environment riêng:

```bash
python -m venv .venv
```

Kích hoạt:

### Windows PowerShell

```powershell
.\.venv\Scripts\Activate.ps1
```

### Linux/macOS

```bash
source .venv/bin/activate
```

---

## 2. Install Dependencies

```bash
pip install -r requirements.txt
```

Nếu Agent có requirements riêng:

```bash
pip install -r agents/requirements.txt
```

---

## 3. Configure OpenAI API Key

Đảm bảo Agent có thể truy cập:

```text
OPENAI_API_KEY
```

Kiểm tra:

```bash
python -c "import os; print(os.getenv('OPENAI_API_KEY'))"
```

---

## 4. Run Agent

Ví dụ:

```bash
python marketing_agent.py
```

hoặc

```bash
python sales_agent.py
```

hoặc

```bash
python code_teacher_agent.py
```

Tùy vào cấu trúc repository.

---

## 5. Verify Agent Startup

Sau khi Agent khởi động:

- Agent đăng ký thành công với Mahilo Server.
- Agent xuất hiện trong danh sách registered agents.
- Agent có thể nhận kết nối WebSocket.

Ví dụ log:

```text
Registered agents:
- SalesAgent
- MarketingAgent
- CodeTeacherAgent
```

---

# Verify Server Connectivity

Từ máy khác trong mạng:

```powershell
Test-NetConnection <SERVER_IP> -Port 8000
```

Ví dụ:

```powershell
Test-NetConnection 10.247.169.84 -Port 8000
```

Kỳ vọng:

```text
TcpTestSucceeded : True
```

---

# Common Issues

## OpenAI API Key Not Found

```text
AuthenticationError
```

Kiểm tra:

```bash
echo $env:OPENAI_API_KEY
```

hoặc:

```bash
python -c "import os; print(os.getenv('OPENAI_API_KEY'))"
```

---

## Mahilo Command Not Found

```text
mahilo : The term 'mahilo' is not recognized ...
```

Giải pháp:

- Xác định vị trí `mahilo.exe`
- Thêm thư mục chứa `mahilo.exe` vào PATH
- Mở lại terminal

---

## Cannot Connect To Server

Kiểm tra:

```powershell
Test-NetConnection <SERVER_IP> -Port 8000
```

Nếu thất bại:

- Kiểm tra firewall
- Kiểm tra server đang bind trên `0.0.0.0`
- Kiểm tra server đã được start hay chưa

---

# Architecture

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

Các Agent có thể trao đổi thông tin với nhau thông qua Mahilo AgentManager hoặc thông qua các API được triển khai riêng.