"""Cấu hình riêng cho từng agent Mahilo khi chạy bằng PydanticAI.

Bản Python gốc khai báo ProductAgent là ``pydantic_ai.Agent(..., result_type=FeatureAnalysis)``
(examples/team_of_agents/pydanticai_product_agent.py). Phần "đặc sản" PydanticAI đó, tức
output có cấu trúc kiểm tra bằng Pydantic, được giữ ở đây. Còn tool của agent thì khai báo ở
phía Scala và chạy trong lõi Mahilo (gọi qua callback), như mọi agent khác.
"""

from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel

# Chỉ dẫn thêm mà PydanticAIAgent bên Mahilo Python gắn vào system prompt.
WAIT_INSTRUCTIONS = """
If you have asked another agent a question already, do not ask it again.
Wait until they reach out to you actively. Meanwhile, you can just wait and
let the user know that you are waiting for the other agent to respond.
"""


class FeatureAnalysis(BaseModel):
    """Giữ nguyên từ pydanticai_product_agent.py."""

    feature_name: str
    priority: int
    requests_for_human: list[str]
    comments: str


@dataclass
class Profile:
    output_type: Any = str
    extra_instructions: str = WAIT_INSTRUCTIONS
    model_settings: dict = field(default_factory=dict)


DEFAULT = Profile()

PROFILES: dict[str, Profile] = {
    "ProductAgent": Profile(output_type=FeatureAnalysis),
}


def for_agent(name: str) -> Profile:
    return PROFILES.get(name, DEFAULT)
