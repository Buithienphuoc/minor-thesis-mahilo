"""Model giả lập để chạy không cần API key (cùng kịch bản với DemoBrain/TeamBrain bên Scala).

Dùng ``FunctionModel`` của PydanticAI: một hàm Python quyết định model "trả lời" gì.
Có hai vai: ``plumber`` (ví dụ dispatcher) và ``ProductAgent`` (ví dụ team_of_agents).
"""

from pydantic_ai.messages import (
    ModelMessage, ModelRequest, ModelResponse, TextPart, ToolCallPart, ToolReturnPart, UserPromptPart,
)
from pydantic_ai.models.function import AgentInfo, FunctionModel

PENDING = "Pending messages: "


def _last_user(messages: list[ModelMessage]) -> str:
    for m in reversed(messages):
        if isinstance(m, ModelRequest):
            for p in m.parts:
                if isinstance(p, UserPromptPart) and isinstance(p.content, str):
                    return p.content
    return ""


def _last_tool(messages: list[ModelMessage]) -> ToolReturnPart | None:
    last = messages[-1]
    if isinstance(last, ModelRequest):
        for p in reversed(last.parts):
            if isinstance(p, ToolReturnPart):
                return p
    return None


def plumber_brain(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
    tool = _last_tool(messages)
    text = _last_user(messages)
    from_queue = text.startswith(PENDING)
    human = text.rsplit("User: ", 1)[-1]

    if from_queue and not tool:
        question = text[len(PENDING):].split(": ", 1)[-1]
        return ModelResponse(parts=[ToolCallPart("contact_human", {"message": f"[PydanticAI] Điều phối viên hỏi: {question}"})])
    if from_queue:
        return ModelResponse(parts=[TextPart("Đã hỏi thợ, đang chờ trả lời.")])
    if not tool:
        return ModelResponse(parts=[ToolCallPart("chat_with_agent", {"agent_name": "dispatcher", "question": f"Plumber says: {human}"})])
    return ModelResponse(parts=[TextPart("Đã chuyển lời của anh cho điều phối viên (qua PydanticAI).")])


def product_brain(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
    """ProductAgent: phân tích tính năng, trả lời agent đã hỏi, rồi kết thúc bằng FeatureAnalysis."""
    tool = _last_tool(messages)
    text = _last_user(messages)
    from_queue = text.startswith(PENDING)
    sender = text[len(PENDING):].split(": ", 1)[0] if from_queue else ""

    if tool is None:
        return ModelResponse(parts=[ToolCallPart("analyze_feature_request", {"feature": "mahilo integration"})])
    if tool.tool_name == "analyze_feature_request" and from_queue:
        return ModelResponse(parts=[ToolCallPart("chat_with_agent", {"agent_name": sender, "question": f"[PydanticAI] {tool.content}"})])

    analysis = {
        "feature_name": "mahilo integration",
        "priority": 1,
        "requests_for_human": ["Approve prioritising the mahilo integration for Q3"],
        "comments": "40% of enterprise customers asked for it; already on the Q3 roadmap.",
    }
    if info.output_tools:  # output có cấu trúc: PydanticAI kết thúc bằng một "output tool"
        return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, analysis)])
    return ModelResponse(parts=[TextPart(str(analysis))])


BRAINS = {"plumber": plumber_brain, "ProductAgent": product_brain}


def demo_model(agent_name: str = "plumber") -> FunctionModel:
    return FunctionModel(BRAINS.get(agent_name, plumber_brain), model_name=f"mahilo-demo-{agent_name}")
