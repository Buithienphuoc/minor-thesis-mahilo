"""ADAPTER cho port ``ToolCaller``: gọi callback HTTP về Mahilo-Scala."""

from typing import Any

import httpx


class HttpToolCaller:
    def __init__(self, callback_url: str, client: httpx.AsyncClient):
        self._url = callback_url.rstrip("/")
        self._client = client

    async def call(self, tool_name: str, tool_call_id: str, arguments: dict[str, Any]) -> str:
        res = await self._client.post(
            f"{self._url}/{tool_name}",
            json={"tool_call_id": tool_call_id, "arguments": arguments},
            timeout=60,
        )
        if res.status_code != 200:
            return f"Error: Mahilo returned HTTP {res.status_code} for tool {tool_name}"
        return res.json().get("result", "")
