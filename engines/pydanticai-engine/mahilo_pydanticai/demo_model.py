"""Model giả lập để chạy không cần API key (giống ``DemoBrain`` bên Scala, vai plumber).

Dùng ``FunctionModel`` của PydanticAI: một hàm Python quyết định model "trả lời" gì.
"""

from pydantic_ai.messages import ModelMessage, ModelRequest, ModelResponse, TextPart, ToolCallPart, ToolReturnPart, UserPromptPart
from pydantic_ai.models.function import AgentInfo, FunctionModel

PENDING = "Pending messages: "


def _last_user(messages: list[ModelMessage]) -> str:
    for m in reversed(messages):
        if isinstance(m, ModelRequest):
            for p in m.parts:
                if isinstance(p, UserPromptPart) and isinstance(p.content, str):
                    return p.content
    return ""


def plumber_brain(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
    last = messages[-1]
    after_tool = isinstance(last, ModelRequest) and any(isinstance(p, ToolReturnPart) for p in last.parts)
    text = _last_user(messages)
    from_queue = text.startswith(PENDING)
    human = text.rsplit("User: ", 1)[-1]

    if from_queue and not after_tool:
        question = text[len(PENDING):].split(": ", 1)[-1]
        return ModelResponse(parts=[ToolCallPart("contact_human", {"message": f"[PydanticAI] Điều phối viên hỏi: {question}"})])
    if from_queue and after_tool:
        return ModelResponse(parts=[TextPart("Đã hỏi thợ, đang chờ trả lời.")])
    if not after_tool:
        return ModelResponse(parts=[ToolCallPart("chat_with_agent", {"agent_name": "dispatcher", "question": f"Plumber says: {human}"})])
    return ModelResponse(parts=[TextPart("Đã chuyển lời của anh cho điều phối viên (qua PydanticAI).")])


def demo_model() -> FunctionModel:
    return FunctionModel(plumber_brain, model_name="mahilo-demo")
