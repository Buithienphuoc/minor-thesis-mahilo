"""Sinh sequence diagram của Mahilo Scala ra hai dạng từ CÙNG một nguồn dữ liệu:

  docs/diagrams/sequence.puml  (PlantUML, sửa/vẽ lại bằng bất kỳ công cụ PlantUML nào)
  docs/diagrams/sequence.svg   (ảnh, GitHub hiển thị trực tiếp)

Chạy lại sau khi sửa:  python3 docs/diagrams/sequence.py
Mũi tên ĐỎ = gọi mạng tới OpenAI API hoặc Anthropic API.
"""
from html import escape
from pathlib import Path

HERE = Path(__file__).parent

# (id, nhãn) theo thứ tự cột từ trái sang phải
PARTICIPANTS = [
    ("admin", "Người chạy server\n(PowerShell 1)"),
    ("khach", "Khách\n(PowerShell 2)"),
    ("cli1", "MahiloCli\nagent 1: dispatcher"),
    ("tho", "Thợ\n(PowerShell 3)"),
    ("cli2", "MahiloCli\nagent 2: plumber"),
    ("main", "ServerMain\n(bootstrap)"),
    ("http", "HttpServer\nMahiloRoutes"),
    ("gw", "WebSocket\nHumanGateway"),
    ("rt", "AgentRuntime\n(lõi Mahilo)"),
    ("worker", "DeliveryWorker"),
    ("broker", "MessageBroker\nPolicyEngine"),
    ("db", "SQLite\nMessageStore + Session"),
    ("eng", "ToolLoopEngine\n(AgentEngine)"),
    ("llm", "LlmClient adapter\nAnthropic / OpenAI"),
    ("api", "Anthropic API\nhoặc OpenAI API"),
]

# Các bước. ("sec", tiêu đề) | ("note", nội dung) | (từ, tới, nhãn, kiểu)
# kiểu: "call" (gọi), "ret" (trả về, nét đứt), "api" (gọi API, đỏ), "apiret" (API trả về, đỏ nét đứt)
STEPS = [
    ("sec", "Start point 1: start server   (sbt bootstrap/run)"),
    ("admin", "main", "sbt bootstrap/run", "call"),
    ("main", "main", "DotEnv.load(.env): MAHILO_LLM, ANTHROPIC_API_KEY / OPENAI_API_KEY", "call"),
    ("main", "llm", "AnthropicLlmClient.resource(...) hoặc OpenAiCompatibleLlmClient(...)  (chỉ tạo client, chưa gọi mạng)", "call"),
    ("main", "main", "buildExample: CentralizedTemplates → tạo agent dispatcher, plumber, mold_specialist", "call"),
    ("main", "db", "SqliteMessageStore.make / SqliteSessionRepository.make (mahilo.db)", "call"),
    ("main", "gw", "WebSocketHumanGateway.make", "call"),
    ("main", "rt", "Mahilo.resource(agents, startActive = [dispatcher], ports)", "call"),
    ("rt", "rt", "activate(dispatcher)", "call"),
    ("rt", "db", "recipientsWithPending  (đánh thức agent còn tin chờ)", "call"),
    ("rt", "worker", "DeliveryWorker.run(queue) chạy nền, chờ queue.take", "call"),
    ("main", "http", "HttpServer.resource(0.0.0.0:8000)", "call"),
    ("main", "admin", "banner: HTTP http://[::]:8000  •  WebSocket ws://localhost:8000/ws/<agent>", "ret"),
    ("note", "Khởi động KHÔNG gọi OpenAI/Anthropic. API key chỉ được dùng khi agent cần suy nghĩ."),

    ("sec", "Start point 2: tạo kết nối cho agent 1 (dispatcher)"),
    ("khach", "cli1", "mahilo-connect.ps1 --agent-name dispatcher", "call"),
    ("cli1", "http", "GET /ws/dispatcher  (WebSocket upgrade)", "call"),
    ("http", "gw", "connect(dispatcher) → hàng đợi outbox", "call"),
    ("cli1", "khach", "Enter message for dispatcher", "ret"),

    ("sec", "Start point 3: tạo kết nối cho agent 2 (plumber)"),
    ("tho", "cli2", "mahilo-connect.ps1 --agent-name plumber", "call"),
    ("cli2", "http", "GET /ws/plumber  (WebSocket upgrade)", "call"),
    ("http", "gw", "connect(plumber) → hàng đợi outbox", "call"),
    ("cli2", "tho", "Enter message for plumber", "ret"),
    ("note", "plumber chưa active: gõ tin lúc này sẽ nhận \"Agent plumber is not active\". Nó được bật khi dispatcher nhắn tới."),

    ("sec", "Hội thoại A: khách → dispatcher"),
    ("khach", "cli1", "\"Toilet nhà tôi bị tắc\"", "call"),
    ("cli1", "http", "WebSocket text frame", "call"),
    ("http", "rt", "chat(dispatcher, text)", "call"),
    ("rt", "db", "sessions.load(dispatcher) + hội thoại của agent khác", "call"),
    ("rt", "eng", "run(AgentTurn: system prompt, tools = [chat_with_agent])", "call"),
    ("eng", "llm", "complete(LlmRequest)", "call"),
    ("llm", "api", "POST /v1/messages  |  POST /v1/chat/completions", "api"),
    ("api", "llm", "tool_use: chat_with_agent(plumber, ...)", "apiret"),
    ("llm", "eng", "LlmReply(toolCalls)", "ret"),
    ("eng", "rt", "executeTool(chat_with_agent)", "call"),
    ("rt", "rt", "activate(plumber) → wakeUp(plumber)", "call"),
    ("rt", "broker", "send(dispatcher → plumber, câu hỏi)", "call"),
    ("broker", "api", "(chỉ khi bật policy dạng lời văn) NaturalLanguageJudge chấm tin", "api"),
    ("broker", "db", "store.save(envelope, Pending)", "call"),
    ("broker", "worker", "wakeUp(plumber)  (queue.offer)", "call"),
    ("eng", "llm", "complete(... + tool_result \"Message sent to plumber\")", "call"),
    ("llm", "api", "POST lần 2 (kèm kết quả tool)", "api"),
    ("api", "llm", "text: \"Mình đã liên hệ thợ...\"", "apiret"),
    ("eng", "rt", "TurnResult(reply)", "ret"),
    ("rt", "db", "sessions.save(dispatcher)", "call"),
    ("rt", "http", "ChatOutcome.Replied", "ret"),
    ("http", "cli1", "WebSocket text", "ret"),
    ("cli1", "khach", "dispatcher: Mình đã liên hệ thợ...", "ret"),

    ("sec", "Hội thoại B: plumber xử lý hộp thư (chạy nền, song song với bước cuối của A)"),
    ("worker", "rt", "processInbox(plumber)", "call"),
    ("rt", "db", "broker.pending(plumber)", "call"),
    ("rt", "eng", "run(tools = [chat_with_agent, contact_human])", "call"),
    ("eng", "llm", "complete(LlmRequest)", "call"),
    ("llm", "api", "POST", "api"),
    ("api", "llm", "tool_use: contact_human(\"Khách báo toilet tắc, khi nào anh đến được?\")", "apiret"),
    ("eng", "rt", "executeTool(contact_human)", "call"),
    ("rt", "gw", "human.send(plumber, text)", "call"),
    ("gw", "cli2", "WebSocket text", "ret"),
    ("cli2", "tho", "plumber: Khách báo toilet tắc...", "ret"),
    ("eng", "llm", "complete(... + tool_result)", "call"),
    ("llm", "api", "POST lần 2", "api"),
    ("api", "llm", "text (kết thúc lượt)", "apiret"),
    ("rt", "broker", "acknowledge(envelope)", "call"),
    ("broker", "db", "updateState(Processed)", "call"),

    ("sec", "Hội thoại C: thợ trả lời → plumber → dispatcher → khách"),
    ("tho", "cli2", "\"Chiều nay 3h tôi đến được\"", "call"),
    ("cli2", "http", "WebSocket text frame", "call"),
    ("http", "rt", "chat(plumber, text)", "call"),
    ("rt", "eng", "run(...)", "call"),
    ("eng", "llm", "complete(LlmRequest)", "call"),
    ("llm", "api", "POST", "api"),
    ("api", "llm", "tool_use: chat_with_agent(dispatcher, \"Plumber says: 3h chiều\")", "apiret"),
    ("rt", "broker", "send(plumber → dispatcher)", "call"),
    ("broker", "db", "store.save(Pending)", "call"),
    ("broker", "worker", "wakeUp(dispatcher)", "call"),
    ("eng", "llm", "complete(... + tool_result)", "call"),
    ("llm", "api", "POST lần 2", "api"),
    ("api", "llm", "text", "apiret"),
    ("rt", "http", "ChatOutcome.Replied", "ret"),
    ("http", "cli2", "WebSocket text", "ret"),
    ("worker", "rt", "processInbox(dispatcher)", "call"),
    ("rt", "eng", "run(tools = [chat_with_agent, contact_human])", "call"),
    ("eng", "llm", "complete(LlmRequest)", "call"),
    ("llm", "api", "POST", "api"),
    ("api", "llm", "tool_use: contact_human(\"Cập nhật từ thợ: 3h chiều\")", "apiret"),
    ("rt", "gw", "human.send(dispatcher, text)", "call"),
    ("gw", "cli1", "WebSocket text", "ret"),
    ("cli1", "khach", "dispatcher: Cập nhật từ thợ: 3h chiều", "ret"),
    ("eng", "llm", "complete(... + tool_result)", "call"),
    ("llm", "api", "POST lần 2", "api"),
    ("api", "llm", "text (kết thúc lượt)", "apiret"),
]


# ---------------------------------------------------------------- PlantUML
def puml() -> str:
    out = ["@startuml", "title Mahilo Scala: sequence diagram (mũi tên đỏ = gọi OpenAI / Anthropic API)",
           "skinparam sequenceMessageAlign left", "skinparam responseMessageBelowArrow true"]
    for pid, label in PARTICIPANTS:
        kind = "actor" if pid in ("admin", "khach", "tho") else "participant"
        out.append(f'{kind} "{label.replace(chr(10), chr(92) + "n")}" as {pid}')
    arrows = {"call": "->", "ret": "-->", "api": "-[#red]>", "apiret": "-[#red]->"}
    for s in STEPS:
        if s[0] == "sec":
            out.append(f"== {s[1]} ==")
        elif s[0] == "note":
            out.append(f"note across: {s[1]}")
        else:
            a, b, text, kind = s
            label = text.replace('"', "'") if kind not in ("api", "apiret") else f"<color:red>{text}</color>"
            out.append(f"{a} {arrows[kind]} {b}: {label}")
    out.append("@enduml")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- SVG
COL_W, LEFT, TOP, ROW, HEAD_H = 150, 30, 70, 30, 46
RED, INK, MUTED, LINE = "#d0021b", "#1f2328", "#57606a", "#8c959f"


def svg() -> str:
    x = {pid: LEFT + COL_W // 2 + i * COL_W for i, (pid, _) in enumerate(PARTICIPANTS)}
    width = LEFT * 2 + COL_W * len(PARTICIPANTS)
    body: list[str] = []
    y = TOP + HEAD_H + 30
    for s in STEPS:
        if s[0] == "sec":
            y += 14
            body.append(f'<rect x="{LEFT}" y="{y - 16}" width="{width - 2 * LEFT}" height="24" fill="#eef1f4" stroke="{LINE}"/>')
            body.append(f'<text x="{width / 2}" y="{y}" text-anchor="middle" font-weight="700">{escape(s[1])}</text>')
            y += ROW + 4
            continue
        if s[0] == "note":
            body.append(f'<rect x="{LEFT + 40}" y="{y - 15}" width="{width - 2 * LEFT - 80}" height="22" fill="#fff8c5" stroke="#d4a72c"/>')
            body.append(f'<text x="{width / 2}" y="{y}" text-anchor="middle" fill="{INK}">{escape(s[1])}</text>')
            y += ROW
            continue
        a, b, text, kind = s
        red = kind in ("api", "apiret")
        color = RED if red else INK
        dash = ' stroke-dasharray="6 4"' if kind in ("ret", "apiret") else ""
        width_px = 2.2 if red else 1.2
        weight = ' font-weight="700"' if red else ""
        marker = "url(#ah-red)" if red else "url(#ah)"
        if a == b:
            xa = x[a]
            body.append(f'<path d="M{xa} {y} h28 v12 h-28" fill="none" stroke="{color}" stroke-width="{width_px}"{dash} marker-end="{marker}"/>')
            body.append(f'<text x="{xa + 34}" y="{y + 9}" fill="{color}"{weight}>{escape(text)}</text>')
            y += ROW + 8
            continue
        xa, xb = x[a], x[b]
        step = -1 if xb < xa else 1
        body.append(f'<line x1="{xa}" y1="{y}" x2="{xb - step * 2}" y2="{y}" stroke="{color}" stroke-width="{width_px}"{dash} marker-end="{marker}"/>')
        tx, anchor = (min(xa, xb) + 6, "start")
        if "api" in (a, b) or (xb < xa and abs(xb - xa) < COL_W * 2):  # cột cuối / mũi tên ngắn sang trái: căn phải
            tx, anchor = (max(xa, xb) - 6, "end")
        body.append(f'<text x="{tx}" y="{y - 5}" text-anchor="{anchor}" fill="{color}"{weight}>{escape(text)}</text>')
        y += ROW
    bottom = y + 10
    height = bottom + HEAD_H + 30

    head: list[str] = []
    for pid, label in PARTICIPANTS:
        cx = x[pid]
        head.append(f'<line x1="{cx}" y1="{TOP + HEAD_H}" x2="{cx}" y2="{bottom}" stroke="{LINE}" stroke-dasharray="3 4"/>')
        for top in (TOP, bottom):
            fill = "#ddf4ff" if pid not in ("api",) else "#ffebe9"
            stroke = "#54aeff" if pid != "api" else RED
            if pid in ("admin", "khach", "tho"):
                fill, stroke = "#f6f8fa", LINE
            head.append(f'<rect x="{cx - COL_W / 2 + 6}" y="{top}" width="{COL_W - 12}" height="{HEAD_H}" rx="6" fill="{fill}" stroke="{stroke}"/>')
            for i, line in enumerate(label.split("\n")):
                head.append(f'<text x="{cx}" y="{top + 19 + i * 16}" text-anchor="middle" font-weight="{700 if i == 0 else 400}">{escape(line)}</text>')

    title = ('<text x="30" y="32" font-size="18" font-weight="700">Mahilo Scala: sequence diagram</text>'
             f'<text x="30" y="54" fill="{MUTED}">Mũi tên <tspan fill="{RED}" font-weight="700">đỏ</tspan> = gọi mạng tới '
             'OpenAI API hoặc Anthropic API. Nét liền = gọi, nét đứt = trả về.</text>')
    defs = (f'<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerUnits="userSpaceOnUse" markerWidth="9" markerHeight="9" orient="auto-start-reverse">'
            f'<path d="M0 0 L10 5 L0 10 z" fill="{INK}"/></marker>'
            f'<marker id="ah-red" viewBox="0 0 10 10" refX="9" refY="5" markerUnits="userSpaceOnUse" markerWidth="9" markerHeight="9" orient="auto-start-reverse">'
            f'<path d="M0 0 L10 5 L0 10 z" fill="{RED}"/></marker></defs>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" '
            f'font-family="Segoe UI, Helvetica, Arial, sans-serif" font-size="12" fill="{INK}">'
            f'<rect width="100%" height="100%" fill="#ffffff"/>{defs}{title}{"".join(head)}{"".join(body)}</svg>\n')


if __name__ == "__main__":
    (HERE / "sequence.puml").write_text(puml(), encoding="utf-8")
    (HERE / "sequence.svg").write_text(svg(), encoding="utf-8")
    print("wrote", HERE / "sequence.puml", "and", HERE / "sequence.svg")
